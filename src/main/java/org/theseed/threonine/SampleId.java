/**
 *
 */
package org.theseed.threonine;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

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
    private static final int STRAIN_SIZE = 7;
    /** array index of pseudo-numeric component */
    private static final int TIME_IDX = 8;
    /** number of guaranteed fields */
    private static final int NORMAL_SIZE = 10;
    /** index of the deletion column */
    private static final int DELETE_COL = 6;
    /** index of the induction column */
    private static final int INDUCE_COL = 7;

    /**
     * Construct a sample ID from an ID string.
     */
    public SampleId(String sampleData) {
        this.fragments = StringUtils.split(sampleData, '_');
        String timeString = StringUtils.replaceChars(fragments[TIME_IDX], 'p', '.');
        this.timePoint = Double.valueOf(timeString);
    }

    @Override
    public int compareTo(SampleId o) {
        int retVal = 0;
        for (int i = 0; retVal == 0 && i < NORMAL_SIZE; i++) {
            if (i == TIME_IDX)
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

}
