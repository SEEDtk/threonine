/**
 *
 */
package org.theseed.reports;

/**
 * This utility object contains a prediction value and a production value for a single sample.
 */
public class PredProd implements Comparable<PredProd> {

    /** prediction level */
    private double prediction;
    /** production level */
    private double production;
    /** ID number */
    private int id;
    /** next available ID number */
    private static int NEXT_ID = 1;

    /**
     * Construct a prediction/production object.
     *
     * @param pred		prediction level
     * @param prod		production level
     */
    public PredProd(double pred, double prod) {
        this.prediction = pred;
        this.production = prod;
        this.id = NEXT_ID;
        NEXT_ID++;
    }

    /**
     * We sort by prediction, then production (both descending), then ID
     */
    @Override
    public int compareTo(PredProd o) {
        int retVal = Double.compare(o.prediction, this.prediction);
        if (retVal == 0) {
            retVal = Double.compare(o.production, this.production);
            if (retVal == 0)
                retVal = this.id - o.id;
        }
        return retVal;
    }

    /**
     * @return 1 if the prediction level is greater than or equal to the specified value, else 0
     *
     * @param cutoff		value to check
     */
    public int isPrediction(double cutoff) {
        return (this.prediction >= cutoff ? 1 : 0);
    }

    /**
     * @return 1 if the production level is greater than or equal to the specified value, else 0
     *
     * @param cutoff		value to check
     */
    public int isProduction(double cutoff) {
        return (this.production >= cutoff ? 1 : 0);
    }

    /**
     * @return the prediction
     */
    public double getPrediction() {
        return this.prediction;
    }

    /**
     * @return the production
     */
    public double getProduction() {
        return this.production;
    }

    /**
     * @return the absolute error
     */
    public double getAbsError() {
        return Math.abs(this.prediction - this.production);
    }

}
