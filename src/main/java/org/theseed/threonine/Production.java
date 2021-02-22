/**
 *
 */
package org.theseed.threonine;

/**
 * This is a helper class where the classification levels for threonine production are stored.
 *
 * @author Bruce Parrello
 *
 */
public class Production {

    // FIELDS
    /** level names */
    private static final String[] LEVEL_NAMES = new String[] { "None", "Low", "High" };
    /** max output for each level */
    private static final double[] LEVEL_MAXS = new double[] { 0.0, 0.8 };

    /**
     * @return the class name for a production level
     *
     * @param prod	production amount to classify
     */
    public static String getLevel(double prod) {
        int i = 0;
        while (i < LEVEL_MAXS.length && prod > LEVEL_MAXS[i]) i++;
        String retVal = LEVEL_NAMES[i];
        return retVal;
    }

    public static String[] getNames() {
        return LEVEL_NAMES;
    }
}
