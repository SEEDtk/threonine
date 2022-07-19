/**
 *
 */
package org.theseed.threonine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.io.TabbedLineReader;
import org.theseed.samples.SampleId;
import org.theseed.utils.BasePipeProcessor;
import org.theseed.utils.ParseFailureException;


/**
 * This command produces a report on the strain make-up of each threonine test run.  Each strain will be associated with
 * the first run in which it was tested, and we will output the strain ID, the number of inserts, and the number of deletes.
 *
 * The standard input should contain a flat-file version of the big run master file.  The report will be written to the
 * standard output.
 *
 * The command-line options are as follows.
 *
 * -h	display command-line usavge
 * -v	display more frequent log messages
 * -i	name of the big-run-master flat file (if not STDIN)
 * -o	name of the output file for the report (if not STDOUT)
 *
 * @author Bruce Parrello
 *
 */
public class StrainProcessor extends BasePipeProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(StrainProcessor.class);
    /** column index for sample ID */
    private int sampleIdx;
    /** column index for first-run ID */
    private int runIdx;
    /** map of strains found to run information */
    private Map<String, StrainInfo> strainMap;
    /** map of month names to month indices */
    private static List<String> MONTHS = Arrays.asList("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug",
            "Sep", "Oct", "Nov", "Dec", "TOT");
    /** run ID match pattern */
    private static Pattern RUN_ID = Pattern.compile("(\\d+)([A-Z][A-Za-z]+)");

    /**
     * This class tracks the data we want on strains.
     */
    private class StrainInfo {

        /** ID of first run */
        private String runID;
        /** index number of first run */
        private int runNum;
        /** sample ID for a sample in this run */
        private SampleId sample;

        /**
         * Construct a strain information object.
         *
         * @param runId		ID of the run containing the strain
         * @param sample	sample ID for the string
         *
         * @throws IOException
         */
        public StrainInfo(String runId, SampleId sample) throws IOException {
            this.runID = runId;
            this.runNum = computeRunDate(runId);
            this.sample = sample;
        }

        /**
         * Record a sample for this strain.
         *
         * @param runId		ID of the run
         */
        public void record(String runId) throws IOException {
            int newNum = computeRunDate(runId);
            if (newNum < this.runNum) {
                this.runID = runId;
                this.runNum = newNum;
            }
        }

    }

    /**
     * Comparator for sorting insert and delete sets.
     */
    protected static class SetSorter implements Comparator<Set<String>> {

        @Override
        public int compare(Set<String> o1, Set<String> o2) {
            int retVal = SampleId.setCompare(o1, o2);
            return retVal;
        }

    }

    /**
     * This tracks the data we want on runs.  Runs are sorted by the run index number (which is computed
     * from the date).
     */
    private class RunInfo implements Comparable<RunInfo> {

        /** ID of run */
        private String runId;
        /** index of run */
        private int runIndex;
        /** set of chromosomes */
        private Set<String> chromosomes;
        /** set of strains */
        private Set<String> strains;
        /** set of insert groups */
        private Set<Set<String>> insertCombos;
        /** set of delete groups */
        private Set<Set<String>> deleteCombos;

        /**
         * Construct a new information object for a run.
         */
        protected RunInfo(String runID) {
            this.runId = runID;
            try {
                this.runIndex = computeRunDate(runID);
            } catch (IOException e) {
                // We throw this unchecked.  The run ID should already be validated at this point.
                throw new UncheckedIOException(e);
            }
            this.chromosomes = new HashSet<String>(100);
            this.strains = new HashSet<String>(100);
            this.insertCombos = new TreeSet<Set<String>>(new SetSorter());
            this.deleteCombos = new TreeSet<Set<String>>(new SetSorter());
        }

        @Override
        public int compareTo(RunInfo o) {
            return this.runIndex - o.runIndex;
        }

        /**
         * Record the run information for this strain.
         *
         * @param chrome_id		chromosome ID
         * @param sample		sample ID for the strain
         */
        public void record(String chrome_id, SampleId sample) {
            this.chromosomes.add(chrome_id);
            this.strains.add(sample.toStrain());
            var iSet = sample.getInserts();
            if (! iSet.isEmpty())
                this.insertCombos.add(iSet);

            var dSet = sample.getDeletes();
            if (! dSet.isEmpty())
                this.deleteCombos.add(dSet);
        }

    }

    @Override
    protected void setPipeDefaults() {
    }

    @Override
    protected void validatePipeInput(TabbedLineReader inputStream) throws IOException {
        // Insure we have the columns we need.
        this.sampleIdx = inputStream.findField("sample");
        this.runIdx = inputStream.findField("first_run");
    }

    @Override
    protected void validatePipeParms() throws IOException, ParseFailureException {
    }

    @Override
    protected void runPipeline(TabbedLineReader inputStream, PrintWriter writer) throws Exception {
        // Create the strain ID set.  We use this to prevent the same strain from being output more than once.
        this.strainMap = new HashMap<String, StrainInfo>(2000);
        // Loop through the input file.
        for (var line : inputStream) {
            String sampleString = line.get(this.sampleIdx);
            String runString = line.get(this.runIdx);
            SampleId sample = new SampleId(sampleString);
            String strain = sample.toStrain();
            if (! this.strainMap.containsKey(strain)) {
                // Here we have a new strain.
                this.strainMap.put(strain, new StrainInfo(runString, sample));
            } else {
                // Here we have to update a known strain.
                this.strainMap.get(strain).record(runString);
            }
        }
        log.info("{} strains found.", this.strainMap.size());
        // We need to accumulate the run report data.  Create the run map.
        Map<String, RunInfo> runMap = new HashMap<String, RunInfo>();
        // Create a special run for totalling.
        var total = new RunInfo("99TOT");
        // Accumulate run statistics.
        for (var strainEntry : this.strainMap.entrySet()) {
            var info = strainEntry.getValue();
            // Compute the chromosome.
            SampleId chromosome = new SampleId(info.sample.replaceFragment(SampleId.INSERT_COL, "000"));
            String chrome_id = chromosome.toStrain();
            // Record the run information.
            RunInfo runInfo = runMap.computeIfAbsent(info.runID, x -> new RunInfo(x));
            runInfo.record(chrome_id, info.sample);
            total.record(chrome_id, info.sample);
        }
        // Now we do the run reports.  For each run, we need a list of the delete combinations, the number
        // of chromosomes, a list of the insert combinations, and the number of strains.  We sort the run
        // descriptors first.
        List<RunInfo> runs = new ArrayList<RunInfo>(runMap.values());
        Collections.sort(runs);
        runs.add(total);
        writer.println("run_id\tchromes\tstrains\tinserts\tdeletes");
        for (var runInfo : runs) {
            var iList = new ArrayList<Set<String>>(runInfo.insertCombos);
            var dList = new ArrayList<Set<String>>(runInfo.deleteCombos);
            final int iSize = iList.size();
            final int dSize = dList.size();
            writer.format("%s\t%d\t%d\t%d\t%d%n", runInfo.runId, runInfo.chromosomes.size(), runInfo.strains.size(),
                    iSize, dSize);
            // Now write out the inserts and deletes, one per line.
            for (int i = 0; i < iSize || i < dSize; i++) {
                String insert = "";
                String delete = "";
                if (i < iSize)
                    insert = StringUtils.join(iList.get(i), "-");
                if (i < dSize)
                    delete = "D" + StringUtils.join(dList.get(i), "D");
                writer.println("\t\t\t" + insert + "\t" + delete);
            }
        }

    }

    /**
     * Convert a run ID into a run date.  A run ID consists of a two-digit year number and a three-letter month name.
     * An invalid run ID will cause an IO exception.
     *
     * @param runId		ID of the relevant run
     *
     * @return the approximate date of the run, expressed in months since 2000
     *
     * @throws IOException
     */
    private static int computeRunDate(String runId) throws IOException {
        int retVal = 0;
        Matcher m = RUN_ID.matcher(runId);
        if (! m.matches())
            throw new IOException("Invalid run ID \"" + runId + "\".");
        else {
            String mString = m.group(2);
            String yString = m.group(1);
            int mNum = MONTHS.indexOf(mString);
            if (mNum < 0)
                throw new IOException("Invalid month in run ID \"" + runId + "\".");
            else {
                int y = Integer.valueOf(yString);
                retVal = y*12 + mNum;
            }
        }
        return retVal;
    }

}
