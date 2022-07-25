/**
 *
 */
package org.theseed.reports;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.TextStringBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.io.LineReader;
import org.theseed.samples.SampleId;

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
    /** permissible insert values */
    private String[] insertChoices;
    /** number of output columns for the sample description */
    private int numCols;
    /** choices for IPTG flag */
    private static final String[] IPTG_CHOICES = new String[] { "I", "0" };
    /** choices for time point */
    private static final String[] TIME_POINTS = new String[] { "24" };
    /** choices for medium */
    private static final String[] MEDIA = new String[] { "M1" };
    /** list of derived fragments */
    private static final Set<Integer> DERIVED_PARTS = new TreeSet<Integer>(Arrays.asList(1, 3));
    /** list of derived columns */
    private static final Set<String> DERIVED_COLUMNS = new TreeSet<String>(Arrays.asList("asdD"));

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
        this.choices = new ArrayList<String[]>(n+2);
        this.iChoices = new ArrayList<String[]>(n+2);
        // This will hold the number of output columns required for a sample ID.  We start with 2, for the
        // time and the IPTG flag.
        this.numCols = 2;
        // Read through the choice records.
        try (LineReader reader = new LineReader(choiceFile)) {
            for (int i = 0; i < n; i++) {
                String line = reader.next();
                // Delete any present null case.
                int begin = StringUtils.indexOfAnyBut(line, " ,0");
                if (begin < 0)
                    throw new IOException(String.format("Invalid choice line #%d in %s.", i+1, choiceFile));
                String newLine = line.substring(begin);
                // Get all the choices.
                String[] choiceArray = this.parseChoiceLine(newLine);
                this.numCols += choiceArray.length;
                this.choices.add(choiceArray);
                this.iChoices.add(this.parseChoiceLine(line));
            }
            // Now process the inserts and deletes.
            String line = reader.next();
            this.insertChoices = this.parseChoiceLine(line);
            this.numCols += this.insertChoices.length;
            log.info("{} insertable proteins.", this.insertChoices.length);
            line = reader.next();
            this.deleteChoices = this.parseChoiceLine(line);
            this.numCols += this.deleteChoices.length;
            log.info("{} deletable proteins.", this.deleteChoices.length);
        }
        log.info("{} total specification columns.", this.numCols);
    }

    /**
     * @return an insert string based on the subset of the inserts indicated by the specified integer
     *
     * @param mask	an integer indicating which insert choices to include in the set
     */
    public String insertSubset(int mask) {
        String retVal;
        Set<String> insertSet = this.calculateInserts(mask);
        if (insertSet.isEmpty())
            retVal = "000";
        else
            retVal = StringUtils.join(insertSet, "-");
        return retVal;
    }

    /**
     * @return the set of inserts for the current sample
     *
     * @param mask	insertion mask
     */
    protected Set<String> calculateInserts(int mask) {
        Set<String> insertSet = new TreeSet<String>();
        int i = 0;
        while (mask > 0) {
            if ((mask & 1) == 1)
                insertSet.add(this.insertChoices[i]);
            i++;
            mask >>= 1;
        }
        return insertSet;
    }

    /**
     * @return a delete string based on the subset of the deletes indicated by the specified integer
     *
     * @param mask	an integer indicating which delete choices to include in the set
     */
    public String deleteSubset(int mask) {
        String retVal;
        Set<String> deleteSet = this.calculateDeletes(mask);
        if (deleteSet.isEmpty())
            retVal = "D000";
        else
            retVal = "D" + StringUtils.join(deleteSet, "D");
        return retVal;
    }

    /**
     * @return the set of deletes for the current sample
     *
     * @param mask	an integer indicating which delete choices to include in the set
     */
    protected Set<String> calculateDeletes(int mask) {
        Set<String> retVal = new TreeSet<String>();
        int i = 0;
        while (mask > 0) {
            if ((mask & 1) == 1)
                retVal.add(this.deleteChoices[i]);
            i++;
            mask >>= 1;
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
        System.arraycopy(this.insertChoices, 0, retVal, outIdx, this.insertChoices.length);
        outIdx += this.insertChoices.length;
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
        // Fill in the 1-choices for the inserts and deletes.
        for (String prot : sample.getInserts())
            this.storeOneHot(this.insertChoices, prot, retVal, outIdx);
        outIdx += this.getInsertChoices().length;
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
     * This class iterates through the sample IDs that are one insert or delete away from the
     * samples in the specified set.  The original samples will be returned as part of the output
     * as well.
     */
    public class SampleGenerator implements Iterator<SampleId> {

        // FIELDS
        /** set of samples already produced */
        private Set<SampleId> history;
        /** iterator through the starting sample set */
        private Iterator<SampleId> originalIter;
        /** iterator through the current-sample's set */
        private Iterator<SampleId> currentIter;
        /** next sample ID to return */
        private SampleId nextSample;
        /** average number of new samples per original */
        private int optionCount;

        /**
         * Construct a generator from a set of original samples.
         */
        public SampleGenerator(Set<SampleId> originals) {
            // Compute the average number of samples per original.
            this.optionCount = (ThrSampleFormatter.this.deleteChoices.length + 1) *
                    (ThrSampleFormatter.this.insertChoices.length + 1);
            // Get the iterator through the original sample set.
            this.originalIter = originals.iterator();
            // Initialize the history set.
            this.history = new HashSet<SampleId>(originals.size() * this.optionCount);
            // Prime the iteration.
            this.setupNewOriginal();
        }

        /**
         * Move to the next sample in the orignal set, and create an iterator through the
         * samples generated from it.
         */
        private void setupNewOriginal() {
            // We are going to loop until we run out of originals or find an original with a nonempty
            // generated set.
            Set<SampleId> currentSet = new HashSet<SampleId>(this.optionCount);
            while (this.originalIter.hasNext() && currentSet.isEmpty()) {
                // Get the next sample to use as a base.
                SampleId original = this.originalIter.next();
                // Prime it with the original.
                this.generateDeletes(original, currentSet);
                // Create samples by inserting.
                for (String insert : ThrSampleFormatter.this.insertChoices) {
                    SampleId iSample = original.addInsert(insert);
                    this.generateDeletes(iSample, currentSet);
                }
            }
            // Did we find a set?
            if (currentSet.isEmpty()) {
                // No.  We are done.
                this.nextSample = null;
            } else {
                // Get an iterator through it.
                this.currentIter = currentSet.iterator();
                // Prime with the first sample.
                this.nextSample = this.currentIter.next();
            }
        }

        /**
         * Add the specified original to the current set, then add all samples formed by deleting an
         * additional protein.
         *
         * @param original		original sample to use as a starting point
         * @param currentSet	current set being built
         */
        private void generateDeletes(SampleId original, Set<SampleId> currentSet) {
            // Prime with the original sample.
            this.checkSample(original, currentSet);
            // Create samples by deleting.
            Arrays.stream(ThrSampleFormatter.this.deleteChoices).map(x -> original.addDelete(x))
                    .forEach(x -> this.checkSample(x, currentSet));
        }

        /**
         * Verify that a sample is new, and if it is, add it to the current set.
         *
         * @param sample	ID of the proposed sample
         * @param currSet	current sample set
         */
        private void checkSample(SampleId sample, Set<SampleId> currSet) {
            // Verify the sample is new.
            boolean keep = ! this.history.contains(sample);
            if (keep) {
                // Verify the sample is valid.  It has to be for one of the main strains and it
                // can't insert and delete the same protein at the same time.
                String host = sample.getFragment(0);
                keep = (host.contentEquals("7") || host.contentEquals("M"));
                if (keep) {
                    var deletes = sample.getDeletes();
                    keep = sample.getInserts().stream().allMatch(x -> ! deletes.contains(x));
                }
            }
            // Add it if we are keeping it.
            if (keep) {
                currSet.add(sample);
                this.history.add(sample);
            }
        }
        @Override
        public boolean hasNext() {
            return this.nextSample != null;
        }
        @Override
        public SampleId next() {
            if (this.nextSample == null)
                throw new NoSuchElementException("Attempt to iterate past last generated sample ID.");
            // Get the next sample.
            SampleId retVal = this.nextSample;
            // Set up for the sample after this one.
            if (this.currentIter.hasNext()) {
                // There is more in the current set, so keep going.
                this.nextSample = this.currentIter.next();
            } else {
                // Current set is empty, so start the new one.
                this.setupNewOriginal();
            }
            return retVal;
        }

    }


    /**
     * This class iterates through all the possible sample IDs.
     */
    public class SampleIterator implements Iterator<SampleId> {

        // FIELDS
        /** positions in the different choices for the next item-- base, inserts, deletes, IPTG, TIME, MEDIUM */
        private int[] positions;
        /** number of choices at each position */
        private int[] limits;
        /** TRUE if we are at the end */
        private boolean done;
        /** position for insert mask */
        private int insertPos;
        /** position for delete mask */
        private int deletePos;

        /**
         * Construct an iterator through the sample IDs.
         */
        public SampleIterator() {
            // Position on the first sample to return.
            this.positions = new int[ThrSampleFormatter.this.iChoices.size() + 5];
            Arrays.fill(this.positions, 0);
            // Compute the limits.
            this.limits = new int[this.positions.length];
            int i = 0;
            while (i < ThrSampleFormatter.this.iChoices.size()) {
                this.limits[i] = ThrSampleFormatter.this.iChoices.get(i).length;
                i++;
            }
            // Here we have the number of possible insertion subsets.
            this.limits[i] = 1 << ThrSampleFormatter.this.insertChoices.length;
            this.insertPos = i;
            i++;
            // Now the deletion subsets.
            this.limits[i] = 1 << ThrSampleFormatter.this.deleteChoices.length;
            this.deletePos = i;
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
        public SampleId next() {
            if (this.done) {
                // End-of-list error.
                throw new NoSuchElementException("Attempt to iterate past last sample ID.");
            }
            // Compute the sample string.
            String retVal = this.format().toString();
            // Now increment to the next sample.
            boolean found = findNext();
            // If there is no next sample, insure hasNext() fails.
            if (! found) done = true;
            // Return the sample ID.
            return new SampleId(retVal);
        }

        /**
         * @return the current sample string
         */
        private TextStringBuilder format() {
            // Construct the current sample ID.
            TextStringBuilder retVal = new TextStringBuilder(this.positions.length * 4);
            // Single-choice fragments.
            int i = 0;
            while (i < ThrSampleFormatter.this.choices.size()) {
                retVal.appendSeparator('_');
                retVal.append(computeFragment(i));
                i++;
            }
            // Inserts
            retVal.append('_');
            retVal.append(ThrSampleFormatter.this.insertSubset(this.positions[i]));
            i++;
            // Deletes
            retVal.append('_');
            retVal.append(ThrSampleFormatter.this.deleteSubset(this.positions[i]));
            i++;
            // IPTG
            retVal.append('_');
            String iptg = IPTG_CHOICES[this.positions[i]];
            retVal.append(iptg);
            i++;
            // Time point
            retVal.append('_');
            retVal.append(TIME_POINTS[this.positions[i]]);
            i++;
            // Medium
            retVal.append('_');
            retVal.append(MEDIA[this.positions[i]]);
            i++;
            return retVal;
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
            String host = this.computeFragment(0);
            switch (host) {
            case "7" :
                if (this.computeFragment(3).contentEquals("0"))
                    retVal = false;
                break;
            case "M" :
                if (this.computeFragment(3).contentEquals("A"))
                    retVal = false;
                else if (this.computeFragment(2).contentEquals("0"))
                    retVal = false;
                break;
            default :
                retVal = false;
            }
            if (retVal) {
                switch (this.computeFragment(2)) {
                case "TA":
                case "TA1":
                case "T":
                    if (! this.computeFragment(1).contentEquals("0")) retVal = false;
                    if (! this.computeFragment(3).contentEquals("C")) retVal = false;
                    if (this.computeFragment(4).contentEquals("asdD")) retVal = false;
                    break;
                case "0":
                    if (! this.computeFragment(1).contentEquals("0")) retVal = false;
                    if (! this.computeFragment(3).contentEquals("0") &&
                            ! this.computeFragment(3).contentEquals("A")) retVal = false;
                    if (this.computeFragment(4).contentEquals("asdD")) retVal = false;
                    break;
                default : /* TasdX */
                    if (! this.computeFragment(1).contentEquals("D")) retVal = false;
                    if (! this.computeFragment(3).contentEquals("P")) retVal = false;
                }
                if (retVal) {
                    // Verify that the deletes don't overlap the inserts.
                    var deletes = ThrSampleFormatter.this.calculateDeletes(this.positions[this.deletePos]);
                    retVal = ! ThrSampleFormatter.this.calculateInserts(this.positions[this.insertPos]).stream()
                            .anyMatch(x -> deletes.contains(x));
                }
            }
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
        return this.insertChoices;
    }

    /**
     * Suppress the derived columns.
     *
     * @param keepCols	array flagging columns to keep
     */
    protected void suppressDerived(boolean[] keepCols) {
        int curCol = 0;
        for (int i = 0; i < this.choices.size(); i++) {
            // Get the number of columns for this fragment.
            int nCurr = this.choices.get(i).length;
            // If this is a derived fragment, suppress all its columns.
            if (DERIVED_PARTS.contains(i)) {
                for (int j = 0; j < nCurr; j++)
                    keepCols[curCol + j] = false;
            }
            curCol += nCurr;
        }
        // Get the titles and suppress the derived values.
        String[] titles = this.getTitles();
        for (int i = 0; i < titles.length; i++) {
            if (DERIVED_COLUMNS.contains(titles[i]))
                keepCols[i] = false;
        }
    }

}
