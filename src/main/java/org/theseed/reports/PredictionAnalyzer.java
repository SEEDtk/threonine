/**
 *
 */
package org.theseed.reports;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.OptionalDouble;

/**
 * This structure maintains a collection of prediction/production pairs for a regression model and produces
 * analysis about the performance of various cutoff levels.
 *
 * @author Bruce Parrello
 *
 */
public class PredictionAnalyzer {

    // FIELDS
    /** list of prediction/production pairs */
    private List<PredProd> samples;

    // NESTED CLASSES

    /**
     * This class is used to construct a confusion matrix for a specified prediction cutoff.
     */
    public class Matrix {

        // FIELDS
        /** confusion matrix:  0 = false, 1 = true; first is prediction, second is actual */
        private int[][] confusion;
        /** prediction level cutoff */
        private double cutoff;

        /**
         * Construct the confusion matrix for a specified cutoff level.
         *
         * @param prediction	minimum prediction level for a positive result
         */
        protected Matrix(double prediction) {
            this.recompute(prediction);
        }

        /**
         * Compute the confusion matrix for the specified prediction level
         *
         * @param prediction	minimum prediction level for a positive result
         */
        public void recompute(double prediction) {
            this.cutoff = prediction;
            this.confusion = new int[][] { { 0, 0 }, { 0, 0 } };
            for (PredProd sampleEntry : PredictionAnalyzer.this.samples)
                confusion[sampleEntry.isPrediction(prediction)][sampleEntry.isProduction(prediction)]++;
        }

        /**
         * @return the ratio between an integer and a size value, or 0 if the size is 0
         *
         * @param i		numerator
         * @param size	denominator
         */
        private double safeRatio(int i, int size) {
            return (size == 0 ? 0.0 : ((double) i) / size);
        }

        /**
         * @return the true-positive count
         */
        public int truePositiveCount() {
            return this.confusion[1][1];
        }

        /**
         * @return the true-positive fraction
         */
        public double truePositiveRatio() {
            return this.safeRatio(this.confusion[1][1], PredictionAnalyzer.this.samples.size());
        }

        /**
         * @return the false-positive count
         */
        public int falsePositiveCount() {
            return this.confusion[1][0];
        }

        /**
         * @return the false-positive fraction
         */
        public double falsePositiveRatio() {
            return this.safeRatio(this.confusion[1][0], PredictionAnalyzer.this.samples.size());
        }

        /**
         * @return the false-negative count
         */
        public int falseNegativeCount() {
            return this.confusion[0][1];
        }

        /**
         * @return the false-negative fraction
         */
        public double falseNegativeRatio() {
            return this.safeRatio(this.confusion[0][1], PredictionAnalyzer.this.samples.size());
        }

        /**
         * @return the true-negative count
         */
        public int trueNegativeCount() {
            return this.confusion[0][0];
        }

        /**
         * @return the true-negative fraction
         */
        public double trueNegativeRatio() {
            return this.safeRatio(this.confusion[0][0], PredictionAnalyzer.this.samples.size());
        }

        /**
         * @return the number of samples
         */
        public int size() {
            return PredictionAnalyzer.this.samples.size();
        }

        /**
         * @return the sensitivity (true positive / actual positive)
         */
        public double sensitivity() {
            return this.safeRatio(confusion[1][1], confusion[1][1] + confusion[0][1]);
        }

        /**
         * @return the miss ratio (false positive / actual positive)
         */
        public double missRatio() {
            return this.safeRatio(confusion[0][1], confusion[1][1] + confusion[0][1]);
        }

        /**
         * @return the fallout (false positive / actual negative)
         */
        public double fallout() {
            return this.safeRatio(confusion[1][0], confusion[1][0] + confusion[0][0]);
        }

        /**
         * @return the accuracy ((true positive + true negative) / size)
         */
        public double accuracy() {
            return this.safeRatio(confusion[0][0] + confusion[1][1], PredictionAnalyzer.this.samples.size());
        }

        /**
         * @return the F1-score (TP / (TP + (FP+FN)/2))
         */
        public double f1score() {
            double retVal;
            if (confusion[1][1] == 0)
                retVal = 0.0;
            else
                retVal = confusion[1][1] / (confusion[1][1] + (confusion[1][0] + confusion[0][1])/2.0);
            return retVal;
        }

        /**
         * @return the Matthews Correlation Coefficient
         */
        public double mcc() {
            double denom = ((double) (confusion[0][0] + confusion[0][1])) * (confusion[1][0] + confusion[1][1])
                    * (confusion[0][0] + confusion[1][0]) * (confusion[0][1] + confusion[1][1]);
            double retVal;
            if (denom == 0.0)
                retVal = 0.0;
            else {
                double num = confusion[0][0] * confusion[1][1] - confusion[1][0] * confusion[0][1];
                retVal = num / Math.sqrt(denom);
            }
            return retVal;
        }

        /**
         * @return the prediction cutoff
         */
        public double getCutoff() {
            return this.cutoff;
        }

        /**
         * @return the precision (true positive / predicted positive)
         */
        public double precision() {
            return this.safeRatio(confusion[1][1], confusion[1][1] + confusion[1][0]);
        }

        /**
         * @return the number predicted positive
         */
        public int predictedCount() {
            return this.falsePositiveCount() + this.truePositiveCount();
        }

        /**
         * @return the number producing positive
         */
        public int actualCount() {
            return this.truePositiveCount() + this.falseNegativeCount();
        }

    }

    /**
     * This is a utility class used to assist in computing the AUC.  The objects are sorted by FPR and then
     * reverse TPR.
     */
    public static class XY implements Comparable<XY> {

        /** sensitivity (y-axis) */
        private double tpr;
        /** fallout (x-axis) */
        private double fpr;

        /**
         * Construct the x-y pair from a confusion matrix.
         *
         * @param m		source confusion matrix
         */
        public XY(Matrix m) {
            this.tpr = m.sensitivity();
            this.fpr = m.fallout();
        }

        @Override
        public int compareTo(XY o) {
            int retVal = Double.compare(this.fpr, o.fpr);
            if (retVal == 0)
                retVal = Double.compare(o.tpr, this.fpr);
            return retVal;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            long temp;
            temp = Double.doubleToLongBits(this.fpr);
            result = prime * result + (int) (temp ^ (temp >>> 32));
            temp = Double.doubleToLongBits(this.tpr);
            result = prime * result + (int) (temp ^ (temp >>> 32));
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof XY)) {
                return false;
            }
            XY other = (XY) obj;
            if (Double.doubleToLongBits(this.fpr) != Double.doubleToLongBits(other.fpr)) {
                return false;
            }
            if (Double.doubleToLongBits(this.tpr) != Double.doubleToLongBits(other.tpr)) {
                return false;
            }
            return true;
        }

        /**
         * @return the area between this point and the previous point
         *
         * @param prev		previous point to use
         */
        public double areaFrom(XY prev) {
            return (this.fpr - prev.fpr) * (this.tpr + prev.tpr) / 2;
        }

    }

    /**
     * Construct a new, empty prediction analyzer.
     */
    public PredictionAnalyzer() {
        this.samples = new ArrayList<PredProd>(100);
    }

    /**
     * Construct a new prediction analyzer from a list of pairs.
     *
     * @param pairs		collection of prediction/production pairs to use
     */
    public PredictionAnalyzer(Collection<PredProd> pairs) {
        this.samples = new ArrayList<PredProd>(pairs);
    }

    /**
     * Add a prediction to this analyzer.
     *
     * @param prediction	predicted value
     * @param production	actual value
     */
    public void add(double prediction, double production) {
        this.samples.add(new PredProd(prediction, production));
    }

    /**
     * @return the number of samples in this analyzer
     */
    public int size() {
        return this.samples.size();
    }

    /**
     * @return the confusion matrix for a specified prediction cutoff
     *
     * @param cutoff	minimum prediction level considered to be positive
     */
    public Matrix getMatrix(double cutoff) {
        return this.new Matrix(cutoff);
    }

    /**
     * @return an array of the prediction levels, sorted from highest to lowest
     */
    public double[] getAllPredictions() {
        // This sorts all the samples by prediction level.
        Collections.sort(this.samples);
        // Copy the unique predictions into an array.
        final int n = this.samples.size();
        double[] retVal = new double[n];
        if (! this.samples.isEmpty()) {
            int last = 0;
            retVal[0] = this.samples.get(0).getPrediction();
            for (int i = 1; i < n; i++) {
                var pred = this.samples.get(i).getPrediction();
                if (pred < retVal[last]) {
                    last++;
                    retVal[last] = pred;
                }
            }
            // Contract the array to the number of spaces used.
            retVal = Arrays.copyOf(retVal, last+1);
        }
        return retVal;
    }

    /**
     * @return the maximum production value
     */
    public double getMaxProd() {
        OptionalDouble retVal = this.samples.stream().mapToDouble(x -> x.getProduction()).max();
        return retVal.orElse(0.0);
    }

    /**
     * @return the mean absolute error of the predictions
     */
    public double getMAE() {
        OptionalDouble retVal = this.samples.stream().mapToDouble(x -> x.getAbsError()).average();
        return retVal.orElse(0.0);
    }

    /**
     * @return the mean absolute error of the predictions at or above the cutoff
     *
     * @param cutoff		cutoff to use
     */
    public double getHighMAE(double cutoff) {
        OptionalDouble retVal = this.samples.stream().filter(x -> (x.getProduction() >= cutoff))
                .mapToDouble(x -> x.getAbsError()).average();
        return retVal.orElse(0.0);
    }

    /**
     * @return the mean absolute error of the predictions below the cutoff
     *
     * @param cutoff		cutoff to use
     */
    public double getLowMAE(double cutoff) {
        OptionalDouble retVal = this.samples.stream().filter(x -> (x.getProduction() < cutoff))
                .mapToDouble(x -> x.getAbsError()).average();
        return retVal.orElse(0.0);
    }

    /**
     * @return the list of prediction/production pairs
     */
    public Collection<PredProd> getAllSamples() {
        return this.samples;
    }

}
