package com.tencent.angel.ml.tree.data

import com.tencent.angel.ml.feature.LabeledData
import com.tencent.angel.ml.math2.vector.IntFloatVector

/**
  * Internal representation of LabeledPoint for DecisionTree.
  * This bins feature values based on a subsampled of data as follows:
  *  (a) Continuous features are binned into ranges.
  *  (b) Unordered categorical features are binned based on subsets of feature values.
  *      "Unordered categorical features" are categorical features with low arity used in
  *      multiclass classification.
  *  (c) Ordered categorical features are binned based on feature values.
  *      "Ordered categorical features" are categorical features with high arity,
  *      or any categorical feature used in regression or binary classification.
  *
  * @param label  Label from LabeledPoint
  * @param binnedFeatures  Binned feature values.
  *                        Same length as LabeledPoint.features, but values are bin indices.
  */
private[tree] class TreePoint(val label: Float, val binnedFeatures: Array[Int])
  extends Serializable {
}

private[tree] object TreePoint {

  /**
    * Convert an input dataset into its TreePoint representation,
    * binning feature values in preparation for DecisionTree training.
    * @param input     Input dataset.
    * @param splits    Splits for features, of size (numFeatures, numSplits).
    * @param metadata  Learning and dataset metadata
    * @return  TreePoint dataset representation
    */
  def convertToTreePoint(
                        input: Array[LabeledData],
                        splits: Array[Array[Split]],
                        metadata: DecisionTreeMetadata): Array[TreePoint] = {
    // Construct arrays for featureArity for efficiency in the inner loop.
    val featureArity: Array[Int] = new Array[Int](metadata.numFeatures)
    var featureIndex = 0
    while (featureIndex < metadata.numFeatures) {
      featureArity(featureIndex) = metadata.featureArity.getOrElse(featureIndex, 0)
      featureIndex += 1
    }
    val thresholds: Array[Array[Float]] = featureArity.zipWithIndex.map { case (arity, idx) =>
      if (arity == 0) {
        splits(idx).map(_.asInstanceOf[ContinuousSplit].threshold)
      } else {
        Array.empty[Float]
      }
    }
    input.map { x =>
      TreePoint.labeledPointToTreePoint(x, thresholds, featureArity)
    }
  }


  /**
    * Convert one LabeledPoint into its TreePoint representation.
    * @param thresholds  For each feature, split thresholds for continuous features,
    *                    empty for categorical features.
    * @param featureArity  Array indexed by feature, with value 0 for continuous and numCategories
    *                      for categorical features.
    */
  private def labeledPointToTreePoint(
                                       labeledPoint: LabeledData,
                                       thresholds: Array[Array[Float]],
                                       featureArity: Array[Int]): TreePoint = {
    val numFeatures = labeledPoint.getX.dim().toInt
    val arr = new Array[Int](numFeatures)
    var featureIndex = 0
    while (featureIndex < numFeatures) {
      arr(featureIndex) =
        findBin(featureIndex, labeledPoint, featureArity(featureIndex), thresholds(featureIndex))
      featureIndex += 1
    }
    new TreePoint(labeledPoint.getY.toFloat, arr)
  }

  /**
    * Find discretized value for one (labeledPoint, feature).
    *
    * NOTE: We cannot use Bucketizer since it handles split thresholds differently than the old
    *       (mllib) tree API.  We want to maintain the same behavior as the old tree API.
    *
    * @param featureArity  0 for continuous features; number of categories for categorical features.
    */
  private def findBin(
                       featureIndex: Int,
                       labeledPoint: LabeledData,
                       featureArity: Int,
                       thresholds: Array[Float]): Int = {
    val featureValue = labeledPoint.getX.asInstanceOf[IntFloatVector].get(featureIndex)

    if (featureArity == 0) {
      val idx = java.util.Arrays.binarySearch(thresholds, featureValue)
      if (idx >= 0) {
        idx
      } else {
        -idx - 1
      }
    } else {
      // Categorical feature bins are indexed by feature values.
      if (featureValue < 0 || featureValue >= featureArity) {
        throw new IllegalArgumentException(
          s"DecisionTree given invalid data:" +
            s" Feature $featureIndex is categorical with values in {0,...,${featureArity - 1}," +
            s" but a data point gives it value $featureValue.\n" +
            "  Bad data point: " + labeledPoint.toString)
      }
      featureValue.toInt
    }
  }
}

