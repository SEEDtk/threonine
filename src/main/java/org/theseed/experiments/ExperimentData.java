/**
 *
 */
package org.theseed.experiments;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class describes an experiment.  The experiment starts on a 96-well plate that is measured for growth at
 * four time points.  It ends on a 368-well plate that is measured for Threonine production.  The entire purpose
 * of this class is to associate the growth and production with a fully-described strain, IPTG status, and time
 * point.
 *
 * @author Bruce Parrello
 *
 */
public class ExperimentData implements Iterable<ExperimentData.Result>{

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(ExperimentData.class);
    /** experiment ID */
    private String id;
    /** map from well ID to strain strings */
    private Map<String, String> strainMap;
    /** map from experiment keys to results */
    private SortedMap<Key, Result> resultMap;
    /** minimum-growth limit */
    private static double MIN_GROWTH = 0.001;
    /** key specification string parser */
    private static final Pattern KEY_PATTERN = Pattern.compile("\\s*([0-9.]+)h\\s+([A-Z]\\d+)\\s*");
    /**
     * This nested class contains the key for an experiment result-- the well ID from the 96-well plate
     * and the time point.
     */
    public static class Key implements Comparable<Key> {

        private String well;
        private double timePoint;

        /**
         * Create a result key.
         *
         * @param well			small-plate well identifier
         * @param timePoint		time point of observation
         */
        public Key(String well, double timePoint) {
            this.well = well;
            this.timePoint = timePoint;
        }

        /**
         * @return a new result key created from the specified spec string, or NULL if the string is invalid.
         *
         * @param string	spec string containing the time point, "h", and the original well
         */
        public static Key create(String string) {
            Key retVal = null;
            Matcher m = KEY_PATTERN.matcher(string);
            if (m.matches()) {
                double timePoint = Double.valueOf(m.group(1));
                String well = m.group(2);
                retVal = new Key(well, timePoint);
            }
            return retVal;
        }

        /**
         * @return the well ID
         */
        public String getWell() {
            return this.well;
        }

        /**
         * @return the time point
         */
        public double getTimePoint() {
            return this.timePoint;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            long temp;
            temp = Double.doubleToLongBits(this.timePoint);
            result = prime * result + (int) (temp ^ (temp >>> 32));
            result = prime * result + ((this.well == null) ? 0 : this.well.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            Key other = (Key) obj;
            if (Double.doubleToLongBits(this.timePoint) != Double.doubleToLongBits(other.timePoint))
                return false;
            if (this.well == null) {
                if (other.well != null)
                    return false;
            } else if (!this.well.equals(other.well))
                return false;
            return true;
        }

        /**
         * The comparison is by well ID and then time point, so all the entries for a well are clustered together.
         */
        @Override
        public int compareTo(Key o) {
            int retVal = this.well.compareTo(o.well);
            if (retVal == 0)
                retVal = Double.compare(this.timePoint, o.timePoint);
            return retVal;
        }

    }

    /**
     * This object contains the results from an experiment.
     */
    public class Result {

        private Key id;
        private String strain;
        private boolean iptgFlag;
        private double growth;
        private double production;
        private boolean suspect;

        /**
         * Create a result object for a specified well and time point.
         *
         * @param well			ID of the well
         * @param timePoint		time point of the measurement
         */
        private Result(String well, double timePoint) {
            // Get the strain string.
            String strainString = ExperimentData.this.getStrain(well);
            boolean iptgFlag = strainString.contains("+IPTG");
            if (iptgFlag)
                strainString = StringUtils.remove(strainString, " +IPTG");
            // Initialize the result.
            setup(well, timePoint, strainString, iptgFlag);
        }

        /**
         * Create a result object where the strain is known.
         *
         * @param strainString	old-style strain name
         * @param well			well ID
         * @param timePoint		appropriate time point
         * @param iptg			TRUE if iptg is present, else FALSE
         */
        private Result(String strainString, String well, double timePoint, boolean iptg) {
            setup(well, timePoint, strainString, iptg);
        }

        /**
         * Initialize this result object.
         *
         * @param well			well ID
         * @param timePoint		appropriate time point
         * @param strainString	old-style strain name
         * @param iptg			TRUE if iptg is present, else FALSE
         */
        public void setup(String well, double timePoint, String strainString, boolean iptg) {
            this.strain = strainString.replaceAll("\\s+", " ");
            // Form the key.
            this.id = new Key(well, timePoint);
            // Determine whether or not IPTG is active.
            this.iptgFlag = iptg;
            // Denote we have no growth or production yet.
            this.growth = Double.NaN;
            this.production = Double.NaN;
            this.suspect = false;
        }

        /**
         * @return the bacterial growth
         */
        public double getGrowth() {
            return this.growth;
        }

        /**
         * Specify the bacterial growth.
         *
         * @param growth 	the growth to set
         */
        public void setGrowth(double growth) {
            this.growth = growth;
        }

        /**
         * @return the Threonine production
         */
        public double getProduction() {
            return this.production;
        }

        /**
         * Specify the Threonine production.
         *
         * @param production 	the production to set
         */
        public void setProduction(double production) {
            this.production = production;
        }

        /**
         * @return the result ID
         */
        public Key getId() {
            return this.id;
        }

        /**
         * @return the strain name
         */
        public String getStrain() {
            return this.strain;
        }

        /**
         * @return TRUE if IPTG is active, else FALSE
         */
        public boolean isIptg() {
            return this.iptgFlag;
        }

        /**
         * @return the well ID
         */
        public String getWell() {
            return this.id.getWell();
        }

        /**
         * @return the time point
         */
        public double getTimePoint() {
            return this.id.getTimePoint();
        }

        /**
         * @return TRUE if this result is complete
         */
        public boolean isComplete() {
            return ! Double.isNaN(this.growth) && ! Double.isNaN(this.production);
        }

        /**
         * @return TRUE if this result is probably bad
         */
        public boolean isSuspect() {
            return this.suspect;
        }

        /**
         * Specify whether or not this result is bad
         *
         * @param suspect 	TRUE if the result is bad, else FALSE
         */
        public void setSuspect(boolean suspect) {
            this.suspect = suspect;
        }

    }

    /**
     * Create an experiment with the specified ID.
     *
     * @param id	ID of this experiment
     */
    public ExperimentData(String id) {
        this.id = id;
        this.resultMap = new TreeMap<Key, Result>();
        this.strainMap = new HashMap<String, String>(100);
    }

    /**
     * @return the strain string for a well, or NULL if the well is blank
     *
     * @param well	well ID
     */
    public String getStrain(String well) {
        return this.strainMap.get(well);
    }

    /**
     * Store a result in this structure.  The specs of the result are specified, but not the growth or
     * production data.
     *
     * @param strainString	old-style strain name
     * @param well			well ID
     * @param timePoint		appropriate time point
     * @param iptg			TRUE if iptg is present, else FALSE
     */
    public void store(String strainString, String well, double timePoint, boolean iptg) {
        Result result = this.new Result(strainString, well, timePoint, iptg);
        this.strainMap.put(well, strainString + (iptg ? " +IPTG" : ""));
        this.resultMap.put(new Key(well, timePoint), result);
    }

    /**
     * Remove bad wells.  A bad well is one with zero growth at 24 hours.
     */
    public void removeBadWells() {
        // Build a set of bad wells.
        Set<String> badWells = new HashSet<String>(100);
        for (Map.Entry<Key, Result> resultEntry : this.resultMap.entrySet()) {
            if (resultEntry.getValue().getGrowth() <= MIN_GROWTH) {
                Key key = resultEntry.getKey();
                if (key.getTimePoint() == 24.0)
                    badWells.add(key.getWell());
            }
        }
        log.info("{} bad wells found in experiment {}.", badWells.size(), this.id);
        // Loop through the hash, removing the bad wells.
        this.resultMap.entrySet().removeIf(x -> badWells.contains(x.getValue().getWell()));
        log.info("{} results left after bad-well removal.", this.resultMap.size());
    }


    /**
     * @return the experiment result description for the specified key, or NULL if it does not exist
     *
     * @param key	key for the experiment result of interest
     */
    public Result getResult(Key key) {
        return this.resultMap.get(key);
    }

    /**
     * @return the experiment result description for the specified key, or NULL if it does not exist
     *
     * @param well			well ID for the experiment result of interest
     * @param timePoint		time point for the experiment result of interest
     */
    public Result getResult(String well, double timePoint) {
        Key key = new Key(well, timePoint);
        return this.getResult(key);
    }

    /**
     * @return the experiment ID
     */
    public String getId() {
        return this.id;
    }

    @Override
    public Iterator<Result> iterator() {
        return this.resultMap.values().iterator();
    }

}
