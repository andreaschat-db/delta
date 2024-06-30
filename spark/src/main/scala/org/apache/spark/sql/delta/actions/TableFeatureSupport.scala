/*
 * Copyright (2021) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.delta.actions

import java.util.Locale

import scala.collection.mutable

import org.apache.spark.sql.delta._
import org.apache.spark.sql.delta.DeltaOperations.Operation
import com.fasterxml.jackson.annotation.JsonIgnore

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.delta.actions.TableFeatureProtocolUtils.TABLE_FEATURES_MIN_WRITER_VERSION

/**
 * Trait to be mixed into the [[Protocol]] case class to enable Table Features.
 *
 * Protocol reader version 3 and writer version 7 start to support reader and writer table
 * features. Reader version 3 supports only reader-writer features in an <b>explicit</b> way,
 * by adding its name to `readerFeatures`. Similarly, writer version 7 supports only writer-only
 * or reader-writer features in an <b>explicit</b> way, by adding its name to `writerFeatures`.
 * When reading or writing a table, clients MUST respect all supported features.
 *
 * See also the document of [[TableFeature]] for feature-specific terminologies.
 */
trait TableFeatureSupport { this: Protocol =>

  /** Check if this protocol is capable of adding features into its `readerFeatures` field. */
  def supportsReaderFeatures: Boolean =
    TableFeatureProtocolUtils.supportsReaderFeatures(minReaderVersion)

  /** Check if this protocol is capable of adding features into its `writerFeatures` field. */
  def supportsWriterFeatures: Boolean =
    TableFeatureProtocolUtils.supportsWriterFeatures(minWriterVersion)

  /**
   * Get a new Protocol object that has `feature` supported. Writer-only features will be added to
   * `writerFeatures` field, and reader-writer features will be added to `readerFeatures` and
   * `writerFeatures` fields.
   *
   * If `feature` is already implicitly supported in the current protocol's legacy reader or
   * writer protocol version, the new protocol will not modify the original protocol version,
   * i.e., the feature will not be explicitly added to the protocol's `readerFeatures` or
   * `writerFeatures`. This is to avoid unnecessary protocol upgrade for feature that it already
   * supports.
   */
  def withFeature(feature: TableFeature): Protocol = {
    def shouldAddRead: Boolean = {
      if (supportsReaderFeatures) return true
      if (feature.minReaderVersion <= minReaderVersion) return false

      throw DeltaErrors.tableFeatureRequiresHigherReaderProtocolVersion(
        feature.name,
        minReaderVersion,
        feature.minReaderVersion)
    }

    def shouldAddWrite: Boolean = {
      if (supportsWriterFeatures) return true
      if (feature.minWriterVersion <= minWriterVersion) return false

      throw DeltaErrors.tableFeatureRequiresHigherWriterProtocolVersion(
        feature.name,
        minWriterVersion,
        feature.minWriterVersion)
    }

    var shouldAddToReaderFeatures = feature.isReaderWriterFeature
    var shouldAddToWriterFeatures = true
    if (feature.isLegacyFeature) {
      if (feature.isReaderWriterFeature) {
        shouldAddToReaderFeatures = shouldAddRead
      }
      shouldAddToWriterFeatures = shouldAddWrite
    }

    val protocolWithDependencies = withFeatures(feature.requiredFeatures)
    protocolWithDependencies.withFeature(
      feature.name,
      addToReaderFeatures = shouldAddToReaderFeatures,
      addToWriterFeatures = shouldAddToWriterFeatures)
  }

  /**
   * Get a new Protocol object with multiple features supported.
   *
   * See the documentation of [[withFeature]] for more information.
   */
  def withFeatures(features: Iterable[TableFeature]): Protocol = {
    features.foldLeft(this)(_.withFeature(_))
  }

  /**
   * Get a new Protocol object with an additional feature descriptor. If `addToReaderFeatures` is
   * set to `true`, the descriptor will be added to the protocol's `readerFeatures` field. If
   * `addToWriterFeatures` is set to `true`, the descriptor will be added to the protocol's
   * `writerFeatures` field.
   *
   * The method does not require the feature to be recognized by the client, therefore will not
   * try keeping the protocol's `readerFeatures` and `writerFeatures` in sync. Use with caution.
   */
  private[actions] def withFeature(
      name: String,
      addToReaderFeatures: Boolean,
      addToWriterFeatures: Boolean): Protocol = {
    if (addToReaderFeatures && !supportsReaderFeatures) {
      throw DeltaErrors.tableFeatureRequiresHigherReaderProtocolVersion(
        name,
        currentVersion = minReaderVersion,
        requiredVersion = TableFeatureProtocolUtils.TABLE_FEATURES_MIN_READER_VERSION)
    }
    if (addToWriterFeatures && !supportsWriterFeatures) {
      throw DeltaErrors.tableFeatureRequiresHigherWriterProtocolVersion(
        name,
        currentVersion = minWriterVersion,
        requiredVersion = TableFeatureProtocolUtils.TABLE_FEATURES_MIN_WRITER_VERSION)
    }

    val addedReaderFeatureOpt = if (addToReaderFeatures) Some(name) else None
    val addedWriterFeatureOpt = if (addToWriterFeatures) Some(name) else None

    copy(
      readerFeatures = this.readerFeatures.map(_ ++ addedReaderFeatureOpt),
      writerFeatures = this.writerFeatures.map(_ ++ addedWriterFeatureOpt))
  }

  /**
   * Get a new Protocol object with additional feature descriptors added to the protocol's
   * `readerFeatures` field.
   *
   * The method does not require the features to be recognized by the client, therefore will not
   * try keeping the protocol's `readerFeatures` and `writerFeatures` in sync. Use with caution.
   */
  private[delta] def withReaderFeatures(names: Iterable[String]): Protocol = {
    names.foldLeft(this)(
      _.withFeature(_, addToReaderFeatures = true, addToWriterFeatures = false))
  }

  /**
   * Get a new Protocol object with additional feature descriptors added to the protocol's
   * `writerFeatures` field.
   *
   * The method does not require the features to be recognized by the client, therefore will not
   * try keeping the protocol's `readerFeatures` and `writerFeatures` in sync. Use with caution.
   */
  private[delta] def withWriterFeatures(names: Iterable[String]): Protocol = {
    names.foldLeft(this)(
      _.withFeature(_, addToReaderFeatures = false, addToWriterFeatures = true))
  }

  /**
   * Get all feature names in this protocol's `readerFeatures` field. Returns an empty set when
   * this protocol does not support reader features.
   */
  def readerFeatureNames: Set[String] = this.readerFeatures.getOrElse(Set())

  /**
   * Get a set of all feature names in this protocol's `writerFeatures` field. Returns an empty
   * set when this protocol does not support writer features.
   */
  def writerFeatureNames: Set[String] = this.writerFeatures.getOrElse(Set())

  /**
   * Get a set of all feature names in this protocol's `readerFeatures` and `writerFeatures`
   * field. Returns an empty set when this protocol supports none of reader and writer features.
   */
  @JsonIgnore
  lazy val readerAndWriterFeatureNames: Set[String] = readerFeatureNames ++ writerFeatureNames

  /**
   * Same as above but returns a sequence of [[TableFeature]] instead of a set of feature names.
   */
  @JsonIgnore
  lazy val readerAndWriterFeatures: Seq[TableFeature] =
    readerAndWriterFeatureNames.toSeq.flatMap(TableFeature.featureNameToFeature)

  /**
   * A sequence of native [[TableFeature]]s. This is derived by filtering out all explicitly
   * supported legacy features.
   */
  @JsonIgnore
  lazy val nativeReaderAndWriterFeatures: Seq[TableFeature] =
    readerAndWriterFeatures.filterNot(_.isLegacyFeature)

  /**
   * Get all features that are implicitly supported by this protocol, for example, `Protocol(1,2)`
   * implicitly supports `appendOnly` and `invariants`. When this protocol is capable of requiring
   * writer features, no feature can be implicitly supported.
   */
  @JsonIgnore
  lazy val implicitlySupportedFeatures: Set[TableFeature] = {
    if (supportsReaderFeatures && supportsWriterFeatures) {
      // this protocol uses both reader and writer features, no feature can be implicitly supported
      Set()
    } else {
      TableFeature.allSupportedFeaturesMap.values
        .filter(_.isLegacyFeature)
        .filterNot(supportsReaderFeatures || this.minReaderVersion < _.minReaderVersion)
        .filterNot(supportsWriterFeatures || this.minWriterVersion < _.minWriterVersion)
        .toSet
    }
  }

  /**
   * Get all features that are supported by this protocol, implicitly and explicitly. When the
   * protocol supports table features, this method returns the same set of features as
   * [[readerAndWriterFeatureNames]]; when the protocol does not support table features, this
   * method becomes equivalent to [[implicitlySupportedFeatures]].
   */
  @JsonIgnore
  lazy val implicitlyAndExplicitlySupportedFeatures: Set[TableFeature] = {
    readerAndWriterFeatureNames.flatMap(TableFeature.featureNameToFeature) ++
      implicitlySupportedFeatures
  }

  /**
   * Determine whether this protocol can be safely upgraded to a new protocol `to`. This means:
   *   - all features supported by this protocol are supported by `to`.
   *
   * Examples regarding feature status:
   *   - from `[appendOnly]` to `[appendOnly]` => allowed.
   *   - from `[appendOnly, changeDataFeed]` to `[appendOnly]` => not allowed.
   *   - from `[appendOnly]` to `[appendOnly, changeDataFeed]` => allowed.
   */
  def canUpgradeTo(to: Protocol): Boolean =
    // All features supported by `this` are supported by `to`.
    implicitlyAndExplicitlySupportedFeatures.subsetOf(to.implicitlyAndExplicitlySupportedFeatures)

  /**
   * Determine whether this protocol can be safely downgraded to a new protocol `to`.
   * All we need is the implicit and explicit features between the two protocols to match,
   * excluding the dropped feature. Note, this accounts for cases where we downgrade
   * from table features to legacy protocol versions.
   */
  def canDowngradeTo(to: Protocol, droppedFeatureName: String): Boolean = {
    val thisFeatures = this.implicitlyAndExplicitlySupportedFeatures
    val toFeatures = to.implicitlyAndExplicitlySupportedFeatures
    val droppedFeature = Seq(droppedFeatureName).flatMap(TableFeature.featureNameToFeature)
    (thisFeatures -- droppedFeature) == toFeatures
  }

  /**
   * True if this protocol can be upgraded or downgraded to the 'to' protocol.
   */
  def canTransitionTo(to: Protocol, op: Operation): Boolean = {
    op match {
      case drop: DeltaOperations.DropTableFeature => canDowngradeTo(to, drop.featureName)
      case _ => canUpgradeTo(to)
    }
  }

  /**
   * Merge this protocol with multiple `protocols` to have the highest reader and writer versions
   * plus all explicitly and implicitly supported features.
   */
  def merge(others: Protocol*): Protocol = {
    val protocols = this +: others
    val mergedReaderVersion = protocols.map(_.minReaderVersion).max
    val mergedWriterVersion = protocols.map(_.minWriterVersion).max
    val mergedReaderFeatures = protocols.flatMap(_.readerFeatureNames)
    val mergedWriterFeatures = protocols.flatMap(_.writerFeatureNames)
    val mergedImplicitFeatures = protocols.flatMap(_.implicitlySupportedFeatures)

    val mergedProtocol = Protocol(mergedReaderVersion, mergedWriterVersion)
      .withReaderFeatures(mergedReaderFeatures)
      .withWriterFeatures(mergedWriterFeatures)
      .withFeatures(mergedImplicitFeatures)

    mergedProtocol.downgradeProtocolVersionsIfNeeded
  }

  /**
   * Remove writer feature from protocol. To remove a writer feature we only need to
   * remove it from the writerFeatures set.
   */
  private[delta] def removeWriterFeature(targetWriterFeature: TableFeature): Protocol = {
    require(targetWriterFeature.isRemovable)
    require(!targetWriterFeature.isReaderWriterFeature)
    copy(writerFeatures = writerFeatures.map(_ - targetWriterFeature.name))
  }

  /**
   * Remove reader+writer feature from protocol. To remove a reader+writer feature we need to
   * remove it from the readerFeatures set and the writerFeatures set.
   */
  private[delta] def removeReaderWriterFeature(
      targetReaderWriterFeature: TableFeature): Protocol = {
    require(targetReaderWriterFeature.isRemovable)
    require(targetReaderWriterFeature.isReaderWriterFeature)
    val newReaderFeatures = readerFeatures.map(_ - targetReaderWriterFeature.name)
    val newWriterFeatures = writerFeatures.map(_ - targetReaderWriterFeature.name)
    copy(readerFeatures = newReaderFeatures, writerFeatures = newWriterFeatures)
  }

  /**
   * Remove feature wrapper for removing either Reader/Writer or Writer features. We assume
   * the feature exists in the protocol. There is a relevant validation at
   * [[AlterTableDropFeatureDeltaCommand]]. We also require targetFeature is removable.
   *
   * When the feature to remove is the last explicit table feature of the table we also remove the
   * TableFeatures feature and downgrade the protocol.
   */
  def removeFeature(targetFeature: TableFeature): Protocol = {
    require(targetFeature.isRemovable)
    val newProtocol = targetFeature match {
      case f@(_: ReaderWriterFeature | _: LegacyReaderWriterFeature) =>
        removeReaderWriterFeature(f)
      case f@(_: WriterFeature | _: LegacyWriterFeature) =>
        removeWriterFeature(f)
      case f =>
        throw DeltaErrors.dropTableFeatureNonRemovableFeature(f.name)
    }
    newProtocol.downgradeProtocolVersionsIfNeeded
  }

  /**
   * If the current protocol does not contain any native table features and the remaining
   * set of legacy table features exactly matches a legacy protocol version, it downgrades the
   * protocol to the minimum reader/writer versions required to support the protocol's legacy
   * features.
   *
   * Note, when a table is initialized with table features (3, 7), by default there are no legacy
   * features. After we remove the last native feature we downgrade the protocol to (1, 1).
   */
  def downgradeProtocolVersionsIfNeeded: Protocol = {
    if (!supportsWriterFeatures) return this

    val (minReaderVersion, minWriterVersion) =
      TableFeatureProtocolUtils.minimumRequiredVersions(readerAndWriterFeatures)
    val newProtocol = Protocol(minReaderVersion, minWriterVersion)

    // Ensure the legacy protocol supports features exactly as the current protocol.
    if (this.implicitlyAndExplicitlySupportedFeatures ==
      newProtocol.implicitlyAndExplicitlySupportedFeatures) {
      newProtocol
    } else {
      Protocol(minReaderVersion, TABLE_FEATURES_MIN_WRITER_VERSION)
        .withFeatures(readerAndWriterFeatures)
    }
  }

  /**
   *
   */
  def denormalize: Protocol = {
    if (supportsWriterFeatures) return this

    val (minReaderVersion, minWriterVersion) =
      TableFeatureProtocolUtils.minimumRequiredVersions(implicitlySupportedFeatures.toSeq)

    Protocol(minReaderVersion, TABLE_FEATURES_MIN_WRITER_VERSION)
      .withFeatures(implicitlySupportedFeatures)
  }

  /**
   * Check if a `feature` is supported by this protocol. This means either (a) the protocol does
   * not support table features and implicitly supports the feature, or (b) the protocol supports
   * table features and references the feature.
   */
  def isFeatureSupported(feature: TableFeature): Boolean = {
    // legacy feature + legacy protocol
    (feature.isLegacyFeature && this.implicitlySupportedFeatures.contains(feature)) ||
      // new protocol
      readerAndWriterFeatureNames.contains(feature.name)
  }
}

object TableFeatureProtocolUtils {

  /** Prop prefix in table properties. */
  val FEATURE_PROP_PREFIX = "delta.feature."

  /** Prop prefix in Spark sessions configs. */
  val DEFAULT_FEATURE_PROP_PREFIX = "spark.databricks.delta.properties.defaults.feature."

  /**
   * The string constant "enabled" for uses in table properties.
   * @deprecated
   *   This value is deprecated to avoid confusion with features that are actually enabled by
   *   table metadata. Use [[FEATURE_PROP_SUPPORTED]] instead.
   */
  val FEATURE_PROP_ENABLED = "enabled"

  /** The string constant "supported" for uses in table properties. */
  val FEATURE_PROP_SUPPORTED = "supported"

  /** Min reader version that supports reader features. */
  val TABLE_FEATURES_MIN_READER_VERSION = 3

  /** Min reader version that supports writer features. */
  val TABLE_FEATURES_MIN_WRITER_VERSION = 7

  /** Get the table property config key for the `feature`. */
  def propertyKey(feature: TableFeature): String = propertyKey(feature.name)

  /** Get the table property config key for the `featureName`. */
  def propertyKey(featureName: String): String =
    s"$FEATURE_PROP_PREFIX$featureName"

  /** Get the session default config key for the `feature`. */
  def defaultPropertyKey(feature: TableFeature): String = defaultPropertyKey(feature.name)

  /** Get the session default config key for the `featureName`. */
  def defaultPropertyKey(featureName: String): String =
    s"$DEFAULT_FEATURE_PROP_PREFIX$featureName"

  /**
   * Determine whether a [[Protocol]] with the given reader protocol version is capable of adding
   * features into its `readerFeatures` field.
   */
  def supportsReaderFeatures(readerVersion: Int): Boolean = {
    readerVersion >= TABLE_FEATURES_MIN_READER_VERSION
  }

  /**
   * Determine whether a [[Protocol]] with the given writer protocol version is capable of adding
   * features into its `writerFeatures` field.
   */
  def supportsWriterFeatures(writerVersion: Int): Boolean = {
    writerVersion >= TABLE_FEATURES_MIN_WRITER_VERSION
  }

  /**
   * Get a set of [[TableFeature]]s representing supported features set in a table properties map.
   */
  def getSupportedFeaturesFromTableConfigs(configs: Map[String, String]): Set[TableFeature] = {
    val featureConfigs = configs.filterKeys(_.startsWith(FEATURE_PROP_PREFIX))
    val unsupportedFeatureConfigs = mutable.Set.empty[String]
    val collectedFeatures = featureConfigs.flatMap { case (key, value) =>
      // Feature name is lower cased in table properties but not in Spark session configs.
      // Feature status is not lower cased in any case.
      val name = key.stripPrefix(FEATURE_PROP_PREFIX).toLowerCase(Locale.ROOT)
      val status = value.toLowerCase(Locale.ROOT)
      if (status != FEATURE_PROP_SUPPORTED && status != FEATURE_PROP_ENABLED) {
        throw DeltaErrors.unsupportedTableFeatureStatusException(name, status)
      }
      val featureOpt = TableFeature.featureNameToFeature(name)
      if (!featureOpt.isDefined) {
        unsupportedFeatureConfigs += key
      }
      featureOpt
    }.toSet
    if (unsupportedFeatureConfigs.nonEmpty) {
      throw DeltaErrors.unsupportedTableFeatureConfigsException(unsupportedFeatureConfigs)
    }
    collectedFeatures
  }

  /**
   * Checks if the the given table property key is a Table Protocol property, i.e.,
   * `delta.minReaderVersion`, `delta.minWriterVersion`, ``delta.ignoreProtocolDefaults``, or
   * anything that starts with `delta.feature.`
   */
  def isTableProtocolProperty(key: String): Boolean = {
    key == Protocol.MIN_READER_VERSION_PROP ||
    key == Protocol.MIN_WRITER_VERSION_PROP ||
    key == DeltaConfigs.CREATE_TABLE_IGNORE_PROTOCOL_DEFAULTS.key ||
    key.startsWith(TableFeatureProtocolUtils.FEATURE_PROP_PREFIX)
  }

  /**
   * Returns the minimum reader/writer versions required to support all provided features.
   */
  def minimumRequiredVersions(features: Seq[TableFeature]): (Int, Int) =
    ((features.map(_.minReaderVersion) :+ 1).max, (features.map(_.minWriterVersion) :+ 1).max)
}
