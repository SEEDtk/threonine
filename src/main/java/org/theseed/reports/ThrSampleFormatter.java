/**
 *
 */
package org.theseed.reports;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.TextStringBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.io.LineReader;
import org.theseed.proteins.SampleId;

/**
 * This class contains utilities for converting sample IDs to machine learning inputs.
 *
 * @author Bruce Parrello
 *
 */
public class ThrSampleFormatter {

    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(ThrProductionFormatter.class);
    /** list of choice arrays for parsing */
    private List<String[]> choices;
    /** list of choice arrays for iterating */
    private List<String[]> iChoices;
    /** permissible delete values */
    private String[] deleteChoices;
    /** number of output columns for the sample description */
    private int numCols;
    /** choices for IPTG flag */
    private static final String[] IPTG_CHOICES = new String[] { "0", "I" };
    /** choices for time point */
    private static final String[] TIME_POINTS = new String[] { "24" };
    /** choices for medium */
    private static final String[] MEDIA = new String[] { "M1" };

    /**
     * @return the choices present in a line from the choice file
     *
     * @param line	input line to parse
     */
    protected String[] parseChoiceLine(String line) {
        String[] retVal = StringUtils.split(line, ", ");
        return retVal;
    }

    /**
     * Set up the choice information.
     *
     * @param choiceFile	name of the file containing the choices
     *
     * @throws IOException
     */
    public void setupChoices(File choiceFile) throws IOException {
        int n = SampleId.numBaseFragments();
        this.choices = new ArrayList<String[]>(n);
        this.iChoices = new ArrayList<String[]>(n);
        // This will hold the number of output columns required for a sample ID.  We start with 2, for the
        // time and the IPTG flag.
        this.numCols = 2;
        // Read through the choice records.
        try (LineReader reader = new LineReader(choiceFile)) {
            for (int i = 0; i < n; i++) {
                String line = reader.next();
                // Delete any present null case.
                String newLine = line.substring(StringUtils.indexOfAnyBut(line, " ,0"));
                // Get all the choices.
                String[] choiceArray = this.parseChoiceLine(newLine);
                this.numCols += choiceArray.length;
                this.choices.add(choiceArray);
                this.iChoices.add(this.parseChoiceLine(line));
            }
            // Now process the deletes.
            String line = reader.next();
            this.deleteChoices = this.parseChoiceLine(line);
            this.numCols += this.deleteChoices.length;
            log.info("{} deletable proteins.", this.deleteChoices.length);
        }
        log.info("{} total specification columns.", this.numCols);
    }

    /**
     * @return a delete string based on the subset of the deletes indicated by the specified integer
     *
     * @param mask	an integer indicating which delete choices to include in the set
     */
    public String deleteSubset(int mask) {
        String retVal;
        // For an empty set, use D000.
        if (mask == 0)
            retVal = "D000";
        else {
            // Here we have deletes to string together.
            StringBuilder buffer = new StringBuilder(this.deleteChoices.length * 4);
            // Loop through the mask, processing bits.
            int i = 0;
            while (mask > 0) {
                if ((mask & 1) == 1)
                    buffer.append('D').append(this.deleteChoices[i]);
                i++;
                mask >>= 1;
            }
            retVal = buffer.toString();
        }
        return retVal;
    }

    /**
     * @return an array of labels for the feature columns
     */
    public String[] getTitles() {
        String[] retVal = new String[numCols];
        int outIdx = 0;
        for (String[] choiceA : this.choices) {
            System.arraycopy(choiceA, 0, retVal, outIdx, choiceA.length);
            outIdx += choiceA.length;
        }
        System.arraycopy(this.deleteChoices, 0, retVal, outIdx, this.deleteChoices.length);
        int n = outIdx + this.deleteChoices.length;
        for (int i = outIdx; i < n; i++)
            retVal[i] = "D" + retVal[i];
        outIdx = n;
        retVal[outIdx++] = "IPTG";
        retVal[outIdx++] = "time";
        return retVal;
    }

    /**
     * @return an array of numbers representing the structure of a sample
     *
     * @param sample	ID of the sample in question
     */
    public double[] parseSample(SampleId sample) {
        double[] retVal = new double[numCols];
        Arrays.fill(retVal, 0.0);
        String[] parts = sample.getBaseFragments();
        // This will track the current output location.
        int outIdx = 0;
        // Fill in the 1-hots for the base fragments.
        for (int i = 0; i < parts.length; i++) {
            this.storeOneHot(this.choices.get(i), parts[i], retVal, outIdx);
            outIdx += this.choices.get(i).length;
        }
        // Fill in the 1-choices for the deletes.
        for (String prot : sample.getDeletes())
            this.storeOneHot(this.deleteChoices, prot, retVal, outIdx);
        outIdx += this.deleteChoices.length;
        // Store the IPTG flag.
        if (sample.isIPTG())
            retVal[outIdx] = 1.0;
        outIdx++;
        // Store the time.
        retVal[outIdx++] = sample.getTimePoint();
        return retVal;

    }

    /**
     * Store a one-hot indicator in a section of the output array.
     *
     * @param choiceA	array of choices
     * @param choice	string representing the choice to store
     * @param output	output array
     * @param outIdx	position in the output array of choice 0
     */
    protected void storeOneHot(String[] choiceA, String choice, double[] output, int outIdx) {
        int idx = ArrayUtils.indexOf(choiceA, choice);
        if (idx >= 0)
            output[outIdx + idx] = 1.0;
    }

    /**
     * This class iterates through all the possible sample IDs.
     */
    public class SampleIterator implements Iterator<String> {

        // FIELDS
        /** positions in the different choices for the next item-- base, deletes, IPTG, TIME, MEDIUM */
        private int[] positions;
        /** number of choices at each position */
        private int[] limits;
        /** TRUE if we are at the end */
        private boolean done;

        /**
         * Construct an iterator through the sample IDs.
         */
        public SampleIterator() {
            // Position on the first sample to return.
            this.positions = new int[ThrSampleFormatter.this.iChoices.size() + 4];
            Arrays.fill(this.positions, 0);
            // Compute the limits.
            this.limits = new int[this.positions.length];
            int i = 0;
            while (i < ThrSampleFormatter.this.iChoices.size()) {
                this.limits[i] = ThrSampleFormatter.this.iChoices.get(i).length;
                i++;
            }
            // Here we have the number of possible deletion subsets.
            this.limits[i] = 1 << ThrSampleFormatter.this.deleteChoices.length;
            i++;
            // Next the IPTG flag.
            this.limits[i] = IPTG_CHOICES.length;
            i++;
            // Time points
            this.limits[i] = TIME_POINTS.length;
            i++;
            // Media
            this.limits[i] = MEDIA.length;
            // Get the first possible sample.
            this.done = false;
            if (! checkValidity())
                this.done = ! findNext();
        }

        @Override
        public boolean hasNext() {
            return ! done;
        }

        @Override
        public String next() {
            if (this.done) {
                // End-of-list error.
                throw new NoSuchElementException("Attempt to iterate past last sample ID.");
            }
            // Construct the current sample ID.
            TextStringBuilder retVal = new TextStringBuilder(this.positions.length * 4);
            int i = 0;
            while (i < ThrSampleFormatter.this.choices.size()) {
                retVal.appendSeparator('_');
                retVal.append(computeFragment(i));
                i++;
            }
            // Now we do the deletes.
            retVal.append('_');
            retVal.append(ThrSampleFormatter.this.deleteSubset(this.positions[i]));
            i++;
            // IPTG
            retVal.append('_');
            retVal.append(IPTG_CHOICES[this.positions[i]]);
            i++;
            // Time point
            retVal.append('_');
            retVal.append(TIME_POINTS[this.positions[i]]);
            i++;
            // Medium
            retVal.append('_');
            retVal.append(MEDIA[this.positions[i]]);
            i++;
            // Now increment to the next sample.
            boolean found = findNext();
            // If there is no next sample, insure hasNext() fails.
            if (! found) done = true;
            // Return the sample ID.
            return retVal.toString();
        }

        /**
         * @return the ID fragment at the specified position
         *
         * @param i		position of interest
         */
        private String computeFragment(int i) {
            return ThrSampleFormatter.this.iChoices.get(i)[this.positions[i]];
        }

        /**
         * Find the next valid sample.
         *
         * @return TRUE if one was found, else FALSE
         */
        private boolean findNext() {
            boolean valid = false;
            boolean found = tryNext();
            while (found && ! valid) {
                valid = checkValidity();
                if (! valid)
                    found = tryNext();
            }
            return found;
        }

        /**
         * @return TRUE if this is a valid combination, else FALSE
         *
         * NOTE that this is not at all robust.
         */
        private boolean checkValidity() {
            boolean retVal = true;
            if (this.computeFragment(0).contentEquals("7") && this.computeFragment(3).contentEquals("0"))
                retVal = false;
            else if (this.computeFragment(2).startsWith("Tasd") && this.computeFragment(3).contentEquals("asdO"))
                retVal = false;
            else if (this.computeFragment(1).contentEquals("0") && ! this.computeFragment(2).contentEquals("0"))
                retVal = false;
            else if (this.computeFragment(0).contentEquals("M") && this.computeFragment(3).contentEquals("A"))
                retVal = false;
            else if (! this.computeFragment(2).contentEquals("0") && (this.computeFragment(3).contentEquals("0") ||
                    this.computeFragment(3).contentEquals("A")))
                retVal = false;
            if (this.computeFragment(1).contentEquals("D") && (this.computeFragment(3).contentEquals("0") ||
                    this.computeFragment(3).contentEquals("A")))
                retVal = false;
            if (this.computeFragment(2).contentEquals("0") && this.computeFragment(5).contentEquals("000") &&
                    ! this.computeFragment(3).contentEquals("0"))
                retVal = false;
            if (! this.computeFragment(5).contentEquals("000") && (this.computeFragment(3).contentEquals("0") ||
                    this.computeFragment(3).contentEquals("A")))
                retVal = false;
            return retVal;
        }

        /**
         * Move to the next possible sample.
         *
         * @return TRUE if successful, FALSE if we are at the end of the stream.
         */
        private boolean tryNext() {
            boolean found = false;
            for (int i = 0; ! found && i < this.positions.length; i++) {
                this.positions[i]++;
                if (this.positions[i] < this.limits[i])
                    found = true;
                else
                    this.positions[i] = 0;
            }
            return found;
        }

    }

    /**
     * @return the possible deletions
     */
    protected String[] getDeleteChoices() {
        return this.deleteChoices;
    }

    /**
     * @return the possible insertions
     */
    protected String[] getInsertChoices() {
        return this.choices.get(SampleId.INSERT_COL);
    }

}
