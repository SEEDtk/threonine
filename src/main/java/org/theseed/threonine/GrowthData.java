/**
 *
 */
package org.theseed.threonine;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.commons.lang3.StringUtils;

/**
 * This is a utility class that represents the threonine production and the growth density for a
 * threonine sample.  There may be multiple observations for a sample, in which case we take the mean.
 *
 * @author Bruce Parrello
 */
public class GrowthData {

    // FIELDS
    /** old strain ID */
    private String oldStrain;
    /** threonine production amounts in grams/liter */
    private List<Double> production;
    /** optical densities */
    private List<Double> density;
    /** experiment and well */
    private List<String> origins;
    /** time point of measurements */
    private double timePoint;

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
        this.timePoint = time;
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
        this.production.add(prod);
        this.density.add(dens);
        this.origins.add(exp + ":" + well);
    }

    /**
     * Compute an error-corrected mean for a set of numbers.  The raw mean and standard deviation are computed.
     * Values outside the four-sigma range are thrown out, and the error-corrected mean is computed from the
     * result.
     *
     * @param nums		list of input numbers
     *
     * @return the bias-corrected mean
     */
    private static double goodMean(List<Double> nums) {
        double retVal = 0.0;
        if (nums.size() == 1)
            retVal = nums.get(0);
        else if (nums.size() > 1) {
            double sum = 0.0;
            double sqSum = 0.0;
            for (double val : nums) {
                sum += val;
                sqSum += val * val;
            }
            double mean = sum / nums.size();
            double stdv = Math.sqrt((sqSum - mean * mean) / nums.size());
            double min = mean - 2.0 * stdv;
            double max = mean + 2.0 * stdv;
            // Now compute the mean for the values inside the 6-sigma range.
            int count = 0;
            for (double val : nums) {
                if (val >= min && val <= max) {
                    retVal += val;
                    count++;
                }
            }
            retVal /= (double) count;
        }
        return retVal;
    }

    /**
     * @return the production rate
     */
    public double getProduction() {
        return goodMean(this.production);
    }

    /**
     * @return the optical density
     */
    public double getDensity() {
        return goodMean(this.density);
    }

    /**
     * @return the origin string
     */
    public String getOrigins() {
        return StringUtils.join(this.origins, ", ");
    }

    /**
     * @return the normalized production
     */
    public double getNormalizedProduction() {
        List<Double> norms = IntStream.range(0, this.production.size())
                .filter(i -> this.density.get(i) > 0).mapToObj(i -> this.production.get(i) / this.density.get(i))
                .collect(Collectors.toList());
        return goodMean(norms);
    }

    /**
     * @return the production rate
     *
     * @param time	number of hours of growth
     */
    public double getProductionRate() {
        return goodMean(this.production) / this.timePoint;
    }

    /**
     * @return the old strain ID
     */
    public String getOldStrain() {
        return this.oldStrain;
    }

}
