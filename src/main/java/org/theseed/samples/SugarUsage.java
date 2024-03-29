/**
 *
 */
package org.theseed.samples;

/**
 * This is a simple object that describes the sugar usage found in a well.  It contains the
 * computed usage and a suspect-value flag.
 *
 * @author Bruce Parrello
 *
 */
public class SugarUsage {

    // FIELDS
    /** sugar usage */
    private double usage;
    /** suspect-value flag */
    private boolean suspicious;
    /** sugar level base */
    private static double BASE_LEVEL = 35.0;
    /** error limit level */
    private static double MAX_LEVEL = 38.5;
    /** default error factor */
    private static double ERROR_FACTOR = 1.1;

    /**
     * Store the base sugar level and the maximum level for validation.
     *
     * @param base_level	base sugar level
     */
    public static void setLevels(double base_level) {
        BASE_LEVEL = base_level;
        MAX_LEVEL = base_level * ERROR_FACTOR;
    }

    /**
     * Construct a usage value.
     *
     * @param level		sugar level in the well
     */
    public SugarUsage(double level) {
        this.suspicious = (level > MAX_LEVEL);
        this.usage = BASE_LEVEL - level;
        if (this.usage < 0.0) this.usage = 0.0;
    }

    /**
     * @return the sugar usage
     */
    public double getUsage() {
        return this.usage;
    }

    /**
     * @return TRUE if this result is suspicious
     */
    public boolean isSuspicious() {
        return this.suspicious;
    }

    public static void setErrorLevel(double errorFactor) {
        ERROR_FACTOR = errorFactor;
    }


}
