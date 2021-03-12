/**
 *
 */
package org.theseed.threonine;

import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;

/**
 * This version of the expression converter computes the mean expression value for each row, and then it returns 0, 1, or -1
 * depending on whether the value is close to the mean, greater than twice the mean, or less than half the mean.
 *
 * @author Bruce Parrello
 *
 */
public class TriageExpressionConverter extends ExpressionConverter {

    // FIELDS
    /** minimum value for a +1 result */
    private double highLimit;
    /** maximum value for a -1 result */
    private double lowLimit;

    @Override
    protected void processRow() {
        DescriptiveStatistics stats = getStats();
        double triMean = ((stats.getPercentile(25) + stats.getPercentile(75)) / 2.0 + stats.getPercentile(50)) / 2.0;
        this.highLimit = 2.0 * triMean;
        this.lowLimit = triMean / 2.0;
    }

    @Override
    protected double convert(double rawValue) {
        double retVal = 0.0;
        if (rawValue >= this.highLimit)
            retVal = 1.0;
        else if (rawValue <= this.lowLimit)
            retVal = -1.0;
        return retVal;
    }

}
