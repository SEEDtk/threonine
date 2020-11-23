/**
 *
 */
package org.theseed.threonine;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;

/**
 * This class represents a sample ID.  A sample ID consists of 10 to 11 identification fields separated by underscores.
 * they are sorted field by field, with one of the fields being interpreted numerically.
 *
 * @author Bruce Parrello
 *
 */
public class SampleId implements Comparable<SampleId> {

    // FIELDS
    /** array of segments */
    private String[] fragments;
    /** time point */
    private double timePoint;
    /** number of fragments in the strain ID */
    public static final int STRAIN_SIZE = 7;
    /** array index of pseudo-numeric component */
    private static final int TIME_COL = 8;
    /** array index of the medium ID */
    private static final int MEDIA_COL = 9;
    /** number of guaranteed fields */
    private static final int NORMAL_SIZE = 10;
    /** index of the insertion column */
    private static final int INSERT_COL = 5;
    /** index of the deletion column */
    public static final int DELETE_COL = 6;
    /** index of the induction column */
    private static final int INDUCE_COL = 7;
    /** map of sample fragments for each plasmid code */
    private static final Map<String, String[]> PLASMID_MAP = Stream.of(
            new AbstractMap.SimpleEntry<>("pfb6.4.2", StringUtils.split("D_TasdA1_P_asdD", '_')),
            new AbstractMap.SimpleEntry<>("pwt2.1.1", StringUtils.split("D_Tasd_P_asdD", '_')),
            new AbstractMap.SimpleEntry<>("pfb6.4.3", StringUtils.split("D_TasdA_P_asdD", '_')))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    /** map of mis-spelled deletion protein names */
    private static final Map<String, String> PROTEIN_ERRORS = Stream.of(
            new AbstractMap.SimpleEntry<>("rthA", "rhtA"),
            new AbstractMap.SimpleEntry<>("lysCC", "lysC"))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    /** default plasmid coding */
    private static final String[] PLASMID_DEFAULT = new String[] { "0", "0", "0", "asdO" };
    /** map of strain numbers to strain IDs */
    private static final Map<String, String> HOST_MAP = Stream.of(
            new AbstractMap.SimpleEntry<>("277", "7"), new AbstractMap.SimpleEntry<>("926", "M"))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    /** pattern for parsing an old strain name */
    protected static final Pattern OLD_STRAIN_NAME = Pattern.compile("(\\d+)([Dd]\\S+)?(?:\\s+(p\\S+))?(?:\\s+\\+(\\S+))?");
    /** set of invalid deletion proteins */
    protected static final Set<String> BAD_DELETES = new TreeSet<String>(Arrays.asList("asd", "thrABC"));

    /**
     * Construct a sample ID from an ID string.
     *
     * @param sampleData 	ID string
     */
    public SampleId(String sampleData) {
        this.fragments = StringUtils.split(sampleData, '_');
        String timeString = StringUtils.replaceChars(fragments[TIME_COL], 'p', '.');
        this.timePoint = Double.valueOf(timeString);
    }

    /**
     * Construct a blank sample ID.
     */
    private SampleId() {
        this.fragments = new String[NORMAL_SIZE];
        this.timePoint = Double.NaN;
    }

    /**
     * Construct a sample ID from old-style sample information.
     *
     * @param strain	old-style strain name
     * @param time		time point
     * @param iptg		TRUE if IPTG induction was used, else FALSE
     * @param medium	ID of the medium
     *
     * @return the new Sample ID, or NULL if it is invalid
     */
    public static SampleId translate(String strain, double time, boolean iptg, String medium) {
        SampleId retVal = new SampleId();
        retVal.fragments = new String[NORMAL_SIZE];
        Matcher m = OLD_STRAIN_NAME.matcher(strain);
        if (! m.matches())
            retVal = null;
        else {
            // We have a valid strain.  The first group is the host strain.
            String host = m.group(1);
            retVal.fragments[0] = HOST_MAP.getOrDefault(host, host);
            // The third group is the plasmid data.
            String[] plasmidSpecs = PLASMID_MAP.getOrDefault(m.group(3), PLASMID_DEFAULT);
            System.arraycopy(plasmidSpecs, 0, retVal.fragments, 1, 4);
            if (host.contentEquals("277") && m.group(3) == null)
                retVal.fragments[3] = "A";
            // The second group is deletions.
            String deletions = m.group(2);
            if (deletions == null)
                retVal.fragments[DELETE_COL] = "D000";
            else {
                // Here we have real deletes to process.  These are hell to process.
                // The basic strategy is to eat a "d", skip three, push to the next "d",
                // and repeat.  "asd" and "thrabc" are automatically removed.  All characters
                // after the first three are uppercased.  Everything else is lower case.
                List<String> deletes = new ArrayList<String>(11);
                deletions = deletions.toLowerCase();
                int pos = 1;
                while (pos < deletions.length()) {
                    int end = pos + 3;
                    while (end < deletions.length() && deletions.charAt(end) != 'd') end++;
                    String delete = StringUtils.substring(deletions, pos, pos+3) +
                            StringUtils.substring(deletions, pos+3, end).toUpperCase();
                    // We have to deal with some messy stuff.  One of the deletion proteins is occasionally
                    // mis-spelled, and two are redundant, because they are expressed elswhere in the ID.
                    if (! BAD_DELETES.contains(delete)) {
                        if (PROTEIN_ERRORS.containsKey(delete))
                            delete = PROTEIN_ERRORS.get(delete);
                        deletes.add(delete);
                    }
                    pos = end + 1;
                }
                if (deletes.size() == 0)
                    retVal.fragments[DELETE_COL] = "D000";
                else
                    retVal.fragments[DELETE_COL] = "D" + StringUtils.join(deletes, 'D');
            }
            // Now we process the additions.  Here, we also need to repair the lower-casing.
            if (m.group(4) == null)
                retVal.fragments[INSERT_COL] = "000";
            else {
                String insert = m.group(4);
                retVal.fragments[INSERT_COL] = StringUtils.substring(insert, 0, 3) +
                        StringUtils.substring(insert, 3).toUpperCase();
            }
            // Now we save the time.
            retVal.timePoint = time;
            String timeString;
            if (Double.isNaN(time))
                timeString = "ML";
            else {
                timeString = String.format("%1.1f", retVal.timePoint);
                timeString = StringUtils.removeEnd(timeString, ".0");
                timeString = StringUtils.replaceChars(timeString, '.', 'p');
            }
            retVal.fragments[TIME_COL] = timeString;
            // Next the IPTG flag.
            retVal.fragments[INDUCE_COL] = (iptg ? "I" : "0");
            // Finally the medium.
            retVal.fragments[MEDIA_COL] = medium;
        }
        return retVal;
    }

    @Override
    public int compareTo(SampleId o) {
        int retVal = 0;
        for (int i = 0; retVal == 0 && i < NORMAL_SIZE; i++) {
            if (i == TIME_COL)
                retVal = Double.compare(this.timePoint, o.timePoint);
            else
                retVal = this.fragments[i].compareTo(o.fragments[i]);
        }
        // Handle the optional 11th slot.
        if (retVal == 0) {
            String thisRep = (this.fragments.length > NORMAL_SIZE ? this.fragments[NORMAL_SIZE] : "");
            String oRep = (o.fragments.length > NORMAL_SIZE ? o.fragments[NORMAL_SIZE] : "");
            retVal = thisRep.compareTo(oRep);
        }
        return retVal;
    }

    /**
     * @return the strain portion of the sample ID
     */
    public String toStrain() {
        return StringUtils.join(this.fragments, '_', 0, STRAIN_SIZE);
    }

    /**
     * @return the string representative of the sample ID
     */
    public String toString() {
        return StringUtils.join(this.fragments, '_');
    }

    /**
     * @return the deletion set for this sample
     */
    public Set<String> getDeletes() {
        Set<String> retVal = new TreeSet<String>();
        String deletes = this.fragments[DELETE_COL];
        if (! deletes.contentEquals("D000")) {
            String[] parts = StringUtils.split(deletes, 'D');
            for (String part : parts)
                retVal.add(part);
        }
        return retVal;
    }

    /**
     * @return an array of the basic fragments in the strain name (everything but the deletes)
     */
    public String[] getBaseFragments() {
        String[] retVal = Arrays.copyOfRange(this.fragments, 0, DELETE_COL);
        return retVal;
    }

    /**
     * @return an array of all the fragments in the strain name (everything but the deletes)
     */
    public String[] getStrainFragments() {
        String[] retVal = Arrays.copyOfRange(this.fragments, 0, STRAIN_SIZE);
        return retVal;
    }

    /**
     * @return the time point for this sample
     */
    public double getTimePoint() {
        return this.timePoint;
    }

    /**
     * @return TRUE if this sample is induced, else FALSE
     */
    public boolean isIPTG() {
        return this.fragments[INDUCE_COL].contentEquals("I");
    }

    /**
     * @return the number of base fragments
     */
    public static int numBaseFragments() {
        return DELETE_COL;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + Arrays.hashCode(this.fragments);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof SampleId)) {
            return false;
        }
        SampleId other = (SampleId) obj;
        if (!Arrays.equals(this.fragments, other.fragments)) {
            return false;
        }
        return true;
    }

}
