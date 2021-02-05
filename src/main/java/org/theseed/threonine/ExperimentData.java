/**
 *
 */
package org.theseed.threonine;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.io.LineReader;

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
    /** file boundary marker */
    private static final String MARKER_LINE = "//";
    /** minimum-growth limit */
    private static double MIN_GROWTH = 0.001;
    /** key specification string parser */
    private static final Pattern KEY_PATTERN = Pattern.compile("\\s*([0-9.]+)h\\s+([A-Z]\\d+)\\s*");
    /** growth file name string parser */
    private static final Pattern GROWTH_FILE_PATTERN =
            Pattern.compile("set \\S+ ([0-9.]+) hrs \\d+\\.csv");

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
        private Key(String well, double timePoint) {
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

        /**
         * Create a result object for a specified well and time point.
         *
         * @param well			ID of the well
         * @param timePoint		time point of the measurement
         */
        private Result(String well, double timePoint) {
            this.id = new Key(well, timePoint);
            String strainString = ExperimentData.this.getStrain(well);
            // Determine whether or not IPTG is active.
            this.iptgFlag = strainString.contains("+IPTG");
            if (this.iptgFlag)
                this.strain = StringUtils.remove(strainString, " +IPTG");
            else
                this.strain = strainString;
            // Denote we have no growth or production yet.
            this.growth = Double.NaN;
            this.production = Double.NaN;
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

    }

    /**
     * Create a map from time points to growth files.
     *
     * @param inDir		experiment directory
     *
     * @return a map for all the growth files in an experiment directory
     */
    public static Map<Double, File> getGrowthFiles(File inDir) {
        String[] files = inDir.list();
        Map<Double, File> retVal = new TreeMap<Double, File>();
        for (String fileName : files) {
            // Verify this is a file we want.
            Matcher m = GROWTH_FILE_PATTERN.matcher(fileName);
            if (m.matches()) {
                double timePoint = Double.valueOf(m.group(1));
                retVal.put(timePoint, new File(inDir, fileName));
            }
        }
        return retVal;
    }

    /**
     * Create an experiment with the specified ID.
     *
     * @param id	ID of this experiment
     */
    public ExperimentData(String id) {
        this.id = id;
        this.resultMap = new TreeMap<Key, Result>();
    }

    /**
     * Read the key file to associate each well with a strain string.
     *
     * @param keyFile	name of the key field
     *
     * @throws IOException
     */
    public void readKeyFile(File keyFile) throws IOException {
        this.strainMap = new HashMap<String, String>(100);
        try (LineReader inFile = new LineReader(keyFile)) {
            log.info("Reading layout for experiment {}.", this.id);
            // Read the column section.
            Map<String, String> colMap = new HashMap<String, String>(20);
            for (String[] line : inFile.new Section(MARKER_LINE)) {
                if (hasStrain(line))
                    colMap.put(line[0], line[1]);
            }
            log.info("{} columns read for experiment {}.", colMap.size(), this.id);
            // Read the row section.
            for (String[] line : inFile.new Section(MARKER_LINE)) {
                if (hasStrain(line)) {
                    for (Map.Entry<String, String> entry : colMap.entrySet())
                        this.strainMap.put(line[0] + entry.getKey(), entry.getValue() + " " + line[1]);
                }
            }
            // Read the override section.  Note a blank here demands that the well be deleted.
            for (String[] line : inFile.new Section(null)) {
                if (hasStrain(line))
                    this.strainMap.put(line[0], line[1]);
                else
                    this.strainMap.remove(line[0]);
            }
            log.info("{} wells defined for experiment {}.", this.strainMap.size(), this.id);
        }
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
     * Read a growth file.  The growth is put into result objects in the result map
     * and then corrected against the blank well in column 12.  The growth file is
     * a comma-delimited file, and the key columns are the first and third.
     *
     * @param growthFile	growth file to read
     * @param timePoint		time point for the file
     *
     * @throws IOException
     */
    public void readGrowthFile(File growthFile, double timePoint) throws IOException {
        log.info("Reading growth file {} for time point {} in experiment {}.",
                growthFile, timePoint, this.id);
        // This map contains the result list for each row.
        Map<String, List<Result>> rowLists = new TreeMap<String, List<Result>>();
        // This map contains the blank growth for each row.
        Map<String, Double> rowBase = new TreeMap<String, Double>();
        // Loop through the file.
        try (LineReader reader = new LineReader(growthFile)) {
            Iterator<String[]> iter = reader.new SectionIter(null, ",");
            // Skip the header line.
            iter.next();
            // Note the growth is scaled by 10.
            while (iter.hasNext()) {
                String[] line = iter.next();
                String well = line[0];
                String rowChar = line[0].substring(0, 1);
                double growth = Double.valueOf(line[2]) * 10.0;
                if (well.endsWith("12")) {
                    // Here we have a row base, so we save it for normalization.
                    rowBase.put(rowChar, growth);
                } else if (this.strainMap.containsKey(well)) {
                    // Here we have a valid strain in the well.  Create the result.
                    Result result = new Result(well, timePoint);
                    // Store it in the row list.
                    List<Result> rowList = rowLists.computeIfAbsent(rowChar, x -> new ArrayList<Result>(12));
                    rowList.add(result);
                    // Set the growth.
                    result.setGrowth(growth);
                }
            }
            // Now we need to normalize each row by subtracting the blank and bottoming at zero.
            // The normalized result is stored in the result map.
            for (Map.Entry<String, List<Result>> rowEntry : rowLists.entrySet()) {
                String rowChar = rowEntry.getKey();
                double baseGrowth = rowBase.get(rowChar);
                for (Result result : rowEntry.getValue()) {
                    double newGrowth = result.getGrowth() - baseGrowth;
                    if (newGrowth < 0.0) newGrowth = 0.0;
                    result.setGrowth(newGrowth);
                    this.resultMap.put(result.getId(), result);
                }
            }
        }
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
     * Read the production file and store production information in the current results.
     * The production file contains two sections.  The first describes what is in each big-platee
     * well.  The second contains the production value in mg/L.  We must conert it to g/L.
     *
     * @param prodFile	name of the production file to process
     *
     * @throws IOException
     */
    public void readProdFile(File prodFile) throws IOException {
        try (LineReader reader = new LineReader(prodFile)) {
            // We will process both sections in the same order-- left to right,
            // one line at a time.  The keys will be stored in this list, and
            // then read it back when we are parsing the production numbers.
            List<Key> keys = new ArrayList<Key>(400);
            // Loop through the first section.
            Iterator<String[]> iter = reader.new SectionIter(MARKER_LINE, "\t");
            // The first line is a header.
            iter.next();
            // Loop through the data lines.
            while (iter.hasNext()) {
                String[] keyStrings = iter.next();
                // We start at position 1, since position 0 is a label.
                for (int i = 1; i < keyStrings.length; i++) {
                    Key key = Key.create(keyStrings[i]);
                    keys.add(key);
                }
            }
            log.info("{} result keys found in {}.", keys.size(), prodFile);
            // Now we read the second section, containing the growths.
            iter = reader.new SectionIter(MARKER_LINE, "\t");
            iter.next();
            // This tracks our position in the key list.
            Iterator<Key> kIter = keys.iterator();
            // This counts the number of results stored.
            int stored = 0;
            // Loop through the data lines.
            while (iter.hasNext()) {
                String[] prodStrings = iter.next();
                for (int i = 1; i < prodStrings.length; i++) {
                    Result result = this.resultMap.get(kIter.next());
                    if (result != null) {
                        // Here we have a result we want.
                        double prod = Double.valueOf(prodStrings[i]) / 1000.0;
                        result.setProduction(prod);
                        stored++;
                    }
                }
            }
            log.info("{} growth numbers stored for experiment {}.", stored, this.id);
            // Remove incomplete results.
            this.resultMap.entrySet().removeIf(x -> ! x.getValue().isComplete());
            log.info("{} complete results found for experiment {}.",
                    this.resultMap.size(), this.id);
        }
    }

    /**
     * @return TRUE if the current layout file line has a strain string in it
     *
     * @param line	array of two strings, a label and a possible strain string
     */
    private static boolean hasStrain(String[] line) {
        return line.length > 1 && ! line[1].isEmpty() && ! line[1].contentEquals("Blank");
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
