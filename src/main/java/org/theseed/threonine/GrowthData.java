/**
 *
 */
package org.theseed.threonine;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.commons.lang3.StringUtils;
import org.theseed.reports.MeanComputer;

/**
 * This is a utility class that represents the threonine production and the growth density for a
 * threonine sample.  There may be multiple observations for a sample, in which case we take the mean.
 * The objects sort by production (high to low), then time point (low to high), and finally old strain ID.
 *
 * @author Bruce Parrello
 */
public class GrowthData implements Comparable<GrowthData> {

    // FIELDS
    /** old strain ID */
    private String oldStrain;
    /** threonine production amounts in grams/liter */
    private List<Double> production;
    /** for each index value, TRUE if the production level is good */
    private BitSet goodLevels;
    /** saved production value */
    private double productionKey;
    /** optical densities */
    private List<Double> density;
    /** experiment and well */
    private List<String> origins;
    /** time point of measurements */
    private double timePoint;
    /** TRUE if this sample is suspicious */
    private boolean suspicious;
    /** algorithm for computing the mean */
    public static MeanComputer MEAN_COMPUTER = new MeanComputer.Sigma(2);
    /** minimum acceptable density */
    public static double MIN_DENSITY = 0.1;
    /** alert level */
    public static double ALERT_LEVEL = 1.2;

    /**
     * Create a blank growth-data object.
     *
     * @param oldStrainId	original strain ID
     * @param time			time point of measurements
     */
    public GrowthData(String oldStrainId, double time) {
        this.oldStrain = oldStrainId;
        this.production = new ArrayList<Double>();
        this.density = new ArrayList<Double>();
        this.origins = new ArrayList<String>();
        this.goodLevels = new BitSet();
        this.timePoint = time;
        this.suspicious = false;
        this.productionKey = Double.NaN;
    }

    /**
     * Merge a single data row into this object.
     *
     * @param prod	threonine production
     * @param dens	optical density
     * @param exp	experiment ID
     * @param well	well ID
     */
    public void merge(double prod, double dens, String exp, String well) {
        // The new value is good if the density is good enough.
        boolean goodValue = checkDensity(dens);
        this.goodLevels.set(this.production.size(), goodValue);
        // Now add the other values.
        this.production.add(prod);
        this.density.add(dens);
        this.origins.add(exp + ":" + well);
    }

    /**
     * This determines if we have nonzero growth.  If the growth is missing, we always consider it nonzero.
     *
     * @param dens	growth value to check
     *
     * @return TRUE if the specified growth density is nonzero
     */
    private static boolean checkDensity(double dens) {
        return Double.isNaN(dens) || dens >= MIN_DENSITY;
    }

    /**
     * @return the production rate
     *
     * NOTE that since this is a sort field, we save the value the first time it is computed
     */
    public double getProduction() {
        if (Double.isNaN(this.productionKey))
            this.productionKey = MEAN_COMPUTER.goodMean(this.production, this.goodLevels);
        return this.productionKey;
    }

    /**
     * @return the optical density
     */
    public double getDensity() {
        return MEAN_COMPUTER.goodMean(this.density, this.goodLevels);
    }

    /**
     * @return the origin string
     */
    public String getOrigins() {
        return StringUtils.join(this.origins, ", ");
    }

    /**
     * Flag a zero-production value as bad if all the others are higher than the threshold.
     *
     * @param	threshold	threshold to check
     */
    public void removeBadZeroes(double threshold) {
        // This will hold the minimum non-zero value.
        double min = Double.POSITIVE_INFINITY;
        // This will count the zero values.
        int xCount = 0;
        int zCount = 0;
        int zIndex = 0;
        // Get the minimum non-zero value.
        for (int i = 0; i < this.production.size(); i++) {
            double val = this.production.get(i);
            if (checkDensity(this.density.get(i))) {
                if (val > 0.0) {
                    min = val;
                    xCount++;
                }  else {
                    zCount++;
                    zIndex = i;
                }
            }
        }
        if (xCount > 0 && min > threshold && zCount == 1) {
            // Here we want to ignore the zero production value.  It's likely a bad result.
            this.goodLevels.clear(zIndex);
        }
        // If we have no good values, mark this suspicious.
        boolean allBad = IntStream.range(0, this.production.size()).noneMatch(i -> this.goodLevels.get(i));
        if (allBad)
            this.suspicious = true;
    }

    /**
     * Flag the minimum or maximum production level as bad if it is out of range.
     *
     * @param alertRange	maximum allowable production range
     *
     * @return TRUE if the range is good when we're done, else FALSE
     */
    public boolean removeOutlier(double alertRange) {
        boolean retVal = false;
        // We need at least three values for this to be practical.
        int n = this.production.size();
        // Remember the extreme values and their locations.
        double min = Double.POSITIVE_INFINITY;
        int minI = 0;
        double max = Double.NEGATIVE_INFINITY;
        int maxI = 0;
        for (int i = 0; i < n; i++) {
            if (this.goodLevels.get(i)) {
                double val = this.production.get(i);
                if (val > max) {
                    max = val;
                    maxI = i;
                }
                if (val < min) {
                    min = val;
                    minI = i;
                }
            }
        }
        if (max - min <= alertRange)
            retVal = true;
        else if (n >= 3) {
            // We have exceeded the acceptable range, but there might be an outlier.
            // Count the number of outliers with respect to the min and max.
            int veryLow = 0;
            int veryHigh = 0;
            for (int i = 0; i < n; i++) {
                if (max - this.production.get(i) > alertRange) veryLow++;
                if (this.production.get(i) - min > alertRange) veryHigh++;
            }
            // If only one value is out of range, remove it and denote we're ok.
            if (veryHigh == 1 && veryLow > 1) {
                this.goodLevels.clear(maxI);
                retVal = true;
            } else if (veryHigh > 1 && veryLow == 1) {
                this.goodLevels.clear(minI);
                retVal = true;
            }
        }
        return retVal;
    }

    /**
     * @return the normalized production
     */
    public double getNormalizedProduction() {
        double retVal = 0.0;
        List<Double> norms = IntStream.range(0, this.production.size())
                .filter(i -> this.goodLevels.get(i) && Double.isFinite(this.density.get(i)))
                .mapToObj(i -> this.production.get(i) / this.density.get(i)).collect(Collectors.toList());
        if (norms.size() > 0)
            retVal = MEAN_COMPUTER.goodMean(norms, this.goodLevels);
        return retVal;
    }

    /**
     * @return the production rate
     *
     * @param time	number of hours of growth
     */
    public double getProductionRate() {
        return MEAN_COMPUTER.goodMean(this.production, this.goodLevels) / this.timePoint;
    }

    /**
     * @return a displayable list of production values
     */
    public String getProductionList() {
        return IntStream.range(0, this.production.size()).mapToDouble(i -> this.production.get(i))
                .mapToObj(f -> StringUtils.trim(String.format("%6.4f", f))).collect(Collectors.joining(","));
    }

    /**
     * @return the old strain ID
     */
    public String getOldStrain() {
        return this.oldStrain;
    }

    /**
     * @return the range of production values
     */
    public double getProductionRange() {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < this.production.size(); i++) {
            if (this.goodLevels.get(i)) {
                double val = this.production.get(i);
                if (val > max) max = val;
                if (val < min) min = val;
            }
        }
        double retVal = 0.0;
        if (min < max) retVal = max - min;
        return retVal;
    }

    /**
     * @return TRUE if this sample has been marked suspicious
     */
    public boolean isSuspicious() {
        return this.suspicious;
    }

    /**
     * Mark this sample as suspicious.
     */
    public void setSuspicious() {
        this.suspicious = true;
    }

    @Override
    public int compareTo(GrowthData o) {
        int retVal = Double.compare(o.getProduction(), this.getProduction());
        if (retVal == 0) {
            retVal = Double.compare(this.timePoint, o.timePoint);
            if (retVal == 0)
                retVal = this.oldStrain.compareTo(o.oldStrain);
        }
        return retVal;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((this.oldStrain == null) ? 0 : this.oldStrain.hashCode());
        long temp;
        temp = Double.doubleToLongBits(this.timePoint);
        result = prime * result + (int) (temp ^ (temp >>> 32));
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof GrowthData)) {
            return false;
        }
        GrowthData other = (GrowthData) obj;
        if (this.oldStrain == null) {
            if (other.oldStrain != null) {
                return false;
            }
        } else if (!this.oldStrain.equals(other.oldStrain)) {
            return false;
        }
        if (Double.doubleToLongBits(this.timePoint) != Double.doubleToLongBits(other.timePoint)) {
            return false;
        }
        return true;
    }

}
