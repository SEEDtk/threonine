/**
 *
 */
package org.theseed.threonine;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.commons.lang3.RegExUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.stat.correlation.PearsonsCorrelation;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.counters.CountMap;
import org.theseed.excel.CustomWorkbook;
import org.theseed.io.TabbedLineReader;
import org.theseed.reports.PredProd;
import org.theseed.reports.PredictionAnalyzer;
import org.theseed.reports.StrainAnalyzer;
import org.theseed.reports.ThrSampleFormatter;
import org.theseed.samples.SampleId;
import org.theseed.utils.BaseProcessor;
import org.theseed.utils.FloatList;
import org.theseed.utils.ParseFailureException;

/**
 * This command analyzes the individual runs that produced the data in a big production table.  A control file
 * specifies the ID, name, plate-name pattern, and prediction file for each run.  The prediction file is used
 * to determine the samples to run based on data from the previous run, so there will be no prediction file for
 * the first run.  Regardless, we treat the prediction files as optional and only output predictions when they
 * are present.
 *
 * For each sample, we repeat the basic columns:  num, old_strain, sample, thr_production, density, origins,
 * raw_productions, and raw_densities.  To this, we add max_production (maximum production value), the name of
 * the first run containing the sample, and a predicted value from each run with a prediction file.
 *
 * The run control file is a three-column table, tab-delimited with headers, containing the run name in the first
 * column, the plate/well pattern in the second column, and the name of the prediction file in the third.  The
 * prediction files are large, so we make two passes over the production file:  one to get the sample IDs for
 * the predictions we need, and then one for actual processing.
 *
 * General performance analysis is done on all the predictions by the models.  We will also do cutoff-success
 * analysis for first-run predictions.  A first-run prediction is a prediction for a sample that is being run
 * for the first time (on the grounds that we are running it because it was predicted).  For each cutoff value,
 * we display the fraction of first-run samples predicted to be above the cutoff that were actuall above the
 * cutoff.
 *
 * Finally, we will total all the component counts for each cutoff, as well as a special one for the cutoff of
 * 0 that counts everything.
 *
 * The output file will be an Excel spreadsheet.  The first sheet will contain the updated big production table.
 * There will be one additional sheet per prediction file, containing ROC information for each run.
 *
 * The positional parameters are the name of the big production table file, the name of the control file
 * containing the run information, and the name of the output file.
 *
 * The command-line options are as follows.
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 *
 * --scores		list of cutoff scores to use for enrichment computation (default 1.2, 2.0, 4.0)
 * --choices	choice file used to build xmatrix (default: choices.tbl in the same directory as the run
 * 				control file)
 *
 * @author Bruce Parrello
 *
 */
public class BigRunProcessor extends BaseProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(BigRunProcessor.class);
    /** list of runs */
    private RunDescriptor[] runs;
    /** map of samples to predictions (missing predictions are NaN) */
    private Map<SampleId, double[]> predMap;
    /** list of cutoff scores for enrichment computation */
    private FloatList cutoffs;
    /** component counts */
    private Map<Double, CountMap<String>> countMapMap;
    /** number of experiments */
    private int experimentCount;
    /** number of outliers */
    private int outlierCount;
    /** number of good samples */
    private int goodSamples;
    /** number of bad samples */
    private int badSamples;
    /** recording of absolute error per non-outlier experiment */
    private DescriptiveStatistics errors;
    /** number of samples for each try count */
    private CountMap<Integer> tryCounts;
    /** cutoff counts */
    private CountMap<Double> countMapTotals;
    /** production formatter for xmatrix sheet */
    private ThrSampleFormatter formatter;
    /** component-pairing map-- maps each component to a hash of components to statistical summaries of pairings */
    private Map<String, Map<String, DescriptiveStatistics>> pairMap;
    /** constructed strains in good samples */
    private Set<String> constructedStrainSet;
    /** pearson correlation engine */
    private final PearsonsCorrelation computer = new PearsonsCorrelation();
    /** search pattern for insert portion of a strain name */
    private final Pattern INSERT_PART = Pattern.compile("_[^_]+_D");
    /** replace string for removing the insert portion of a strain name */
    private final String NULL_INSERT = "_000_D";

    // COMMAND-LINE OPTIONS

    /** list of cutoff scores for enrichment computation */
    @Option(name = "--scores", metaVar = "1.2,2.0", usage = "comma-delimited list of cutoff scores to test")
    private String scoreList;

    /** name of the choice table */
    @Option(name = "--choices", metaVar = "choices.tbl", usage = "name of the choice file used to generate ML input files")
    private File  choiceFile;

    /** name of the file containing the big production table */
    @Argument(index = 0, metaVar = "big_production_master.tbl", usage = "name of file containing big production table",
            required = true)
    private File bigProdFile;

    /** name of the run control file */
    @Argument(index = 1, metaVar = "runs.control.tbl", usage = "name of the run control table file", required = true)
    private File runFile;

    /** name of the output file */
    @Argument(index = 2, metaVar = "output.xlsx", usage = "output file name", required = true)
    private File outFile;


    // NESTED CLASSES

    /**
     * This is a utility object that contains all the data for a run.
     */
    protected class RunDescriptor {

        /** pattern for plate/well IDs */
        private Pattern wellPattern;
        /** name of the run */
        private String name;
        /** name of the prediction file */
        private File predFile;
        /** prediction analyzer */
        private PredictionAnalyzer analyzer;
        /** strain analyzer */
        private StrainAnalyzer strainData;
        /** first-run prediction analyzer */
        private PredictionAnalyzer analyzer1;
        /** first-run strain analyzer */
        private StrainAnalyzer strainData1;
        /** area under the curve */
        private double auc;
        /** maximum prediction */
        private double maxPred;
        /** maximum production */
        private double maxProdAll;
        /** total number of predicted samples for this run */
        private int runSize;
        /** number of new samples for this run */
        private int newSize;
        /** strain map for this run */
        private Map<String, Double> strains;
        /** strain map for samples new to this run */
        private Map<String, Double> strains1;
        /** maximum production of a constructed sample */
        private double maxProdConstructed;
        /** maximum production of a control sample */
        private double maxProdControl;
        /** total lines in prediction file */
        private int totalPredictions;
        /** prediction file results above lowest cutoff */
        private int highPredictions;
        /** cutoff counts */
        private CountMap<Double> cutoffCounts;

        /**
         * Create a run descriptor.
         *
         * @param line	input line from run control file
         */
        public RunDescriptor(TabbedLineReader.Line line) {
            this.wellPattern = Pattern.compile(line.get(1));
            this.name = line.get(0);
            String predFileName = line.get(2);
            if (StringUtils.isBlank(predFileName))
                this.predFile = null;
            else
                this.predFile = new File(predFileName);
            this.analyzer = new PredictionAnalyzer();
            this.analyzer1 = new PredictionAnalyzer();
            this.strainData = new StrainAnalyzer();
            this.strainData1 = new StrainAnalyzer();
            this.strains = new HashMap<String, Double>(500);
            this.strains1 = new HashMap<String, Double>(500);
            this.runSize = 0;
            this.newSize = 0;
            this.maxProdAll = 0.0;
            this.maxProdConstructed = 0.0;
            this.maxProdControl = 0.0;
            this.totalPredictions = 0;
            this.highPredictions = 0;
            this.cutoffCounts = new CountMap<Double>();
        }

        /**
         * @return TRUE if this run has predictions
         */
        public boolean hasPredictions() {
            return this.predFile != null;
        }

        /**
         * Store the predictions for this run in the prediction map.
         *
         * @param i		array index for this run
         *
         * @throws IOException
         */
        public void storePredictions(int i) throws IOException {
            if (this.predFile != null) {
                log.info("Loading predictions from {}.", this.predFile);
                int lineCount = 0;
                int predCount = 0;
                int highCount = 0;
                double cutoff = BigRunProcessor.this.cutoffs.get(0);
                try (var predStream = new TabbedLineReader(this.predFile)) {
                    for (TabbedLineReader.Line line : predStream) {
                        SampleId sample = new SampleId(line.get(0));
                        double pred = line.getDouble(1);
                        double[] preds = BigRunProcessor.this.predMap.get(sample);
                        if (preds != null) {
                            preds[i] = pred;
                            predCount++;
                        }
                        if (pred >= cutoff) highCount++;
                        lineCount++;
                    }
                    log.info("{} predictions retrieved from {} lines.  {} higher than {}.",
                            predCount, lineCount, highCount, cutoff);
                    this.totalPredictions = lineCount;
                    this.highPredictions = highCount;
                }
            }
        }

        /**
         * @return the name of this run
         */
        public String getName() {
            return this.name;
        }

        /**
         * @return TRUE if the specified origin matches this run
         *
         * @param origin	plate/well string to check
         */
        public boolean isMatch(String origin) {
            // Strip off the parentheses, if needed.
            if (origin.startsWith("("))
                origin = origin.substring(1, origin.length() - 1);
            return this.wellPattern.matcher(origin).matches();
        }

        /**
         * Add a prediction/production pair to this run's analysis.
         *
         * @param sample		ID of sample
         * @param pred			predicted value for this run
         * @param production	actual value of sample
         * @param isNew			TRUE if the sample is new to this run
         */
        public void addPrediction(SampleId sample, double pred, double production, boolean isNew) {
            this.analyzer.add(pred, production);
            this.strainData.add(sample, pred, production);
            if (isNew) {
                this.analyzer1.add(pred, production);
                this.strainData1.add(sample, pred, production);
            }
        }

        /**
         * Merge a sample production into a strain mapping.
         *
         * @param map		strain mapping to update
         * @param strain	strain ID string
         * @param prod		production level
         */
        private void mergeSample(Map<String, Double> strainMap, String strain, double prod) {
            if (! strainMap.containsKey(strain))
                strainMap.put(strain, prod);
            else {
                double oldValue = strainMap.get(strain);
                if (prod > oldValue)
                    strainMap.put(strain, prod);
            }
        }

        /**
         * Record a sample in this run.
         *
         * @param sample		ID of sample
         * @param isNew			TRUE if the sample is new to this run
         * @param production 	production level of the sample
         */
        public void addSample(SampleId sample, boolean isNew, double production) {
            this.runSize++;
            final String strainString = sample.toStrain();
            this.mergeSample(this.strains, strainString, production);
            if (isNew) {
                this.newSize++;
                this.mergeSample(this.strains1, strainString, production);
            }
            if (production > this.maxProdAll)
                this.maxProdAll = production;
            if (sample.isConstructed()) {
                // Record the maximum constructed-strain production.
                if (production > this.maxProdConstructed)
                    this.maxProdConstructed = production;
                // Record the cutoff counts.
                for (var cutoff : BigRunProcessor.this.cutoffs) {
                    if (production > cutoff)
                        this.cutoffCounts.count(cutoff);
                }
            } else if (production > this.maxProdControl)
                this.maxProdControl = production;
        }

        /**
         * @return a sorted array of the prediction levels for this run
         */
        public double[] getAllPredictions() {
            return this.analyzer.getAllPredictions();
        }

        /**
         * @return the strain predictions for this run
         */
        public PredictionAnalyzer getStrainData() {
            return this.strainData.toAnalyzer();
        }

        /**
         * @return the new strain predictions for this run
         */
        public PredictionAnalyzer getStrainData1() {
            return this.strainData1.toAnalyzer();
        }

        /**
         * @return the number of strains for this run
         */
        public int getStrainCount() {
            return this.strains.size();
        }

        /**
         * @return the area-under-the-curve value
         */
        public double getAuc() {
            return this.auc;
        }

        /**
         * Specify the AUC value for this run.
         *
         * @param auc 	the area-under-the-curve value to set
         */
        public void setAuc(double auc) {
            this.auc = auc;
        }

        /**
         * @return the confusion matrix for the specified prediction level
         *
         * @param predLevel		prediction level of interest
         */
        public PredictionAnalyzer.Matrix getMatrix(double predLevel) {
            return this.analyzer.getMatrix(predLevel);
        }

        /**
         * @return the mean absolute error for this run
         */
        public double getMAE() {
            return this.analyzer.getMAE();
        }

        /**
         * @return the number of predictions
         */
        public int size() {
            return this.analyzer.size();
        }

        /**
         * @return the maximum prediction level
         */
        public double getMaxPred() {
            return this.maxPred;
        }

        /**
         * Specify the maximum prediction level.
         *
         * @param maxPred 	the prediction level to set
         */
        public void setMaxPred(double maxPred) {
            this.maxPred = maxPred;
        }

        /**
         * @return the first-run analyzer for this run
         */
        public PredictionAnalyzer getAnalyzer1() {
            return this.analyzer1;
        }

        /**
         * @return the maximum production value for predicted samples in this run
         */
        public double getMaxProd() {
            return this.analyzer.getMaxProd();
        }

        /**
         * @return the run size
         */
        public int getRunSize() {
            return this.runSize;
        }

        /**
         * @return the number of samples new to the run
         */
        public int getNewSize() {
            return this.newSize;
        }

        /**
         * @return the pearson coefficient for the first-run predictions
         */
        public double getPearson() {
            var predProds = this.analyzer1.getAllSamples();
            double[] preds = new double[predProds.size()];
            double[] prods = new double[predProds.size()];
            int i = 0;
            for (PredProd predProd : predProds) {
                preds[i] = predProd.getPrediction();
                prods[i] = predProd.getProduction();
                i++;
            }
            double retVal = BigRunProcessor.this.computer.correlation(preds, prods);
            return retVal;
        }

        /**
         * @return the maximum production for the run
         */
        public double getMaxProdAll() {
            return this.maxProdAll;
        }

        /**
         * @return the number of new strains in this run
         */
        public int getNewStrainCount() {
            return this.strains1.size();
        }

        /**
         * @return the number of constructed strains in this run
         */
        public int getConstructedStrainCount() {
            return (int) getConstructed(this.strains.keySet()).count();
        }

        /**
         * @return the number of constructed strains new to this run
         */
        public int getNewConstructedStrainCount() {
            return (int) getConstructed(this.strains1.keySet()).count();
        }

        /**
         * @return the number of constructed chromosomes in this run
         */
        public int getConstructedChromosomeCount() {
            return getConstructed(this.strains.keySet()).map(x -> toChromosome(x)).collect(Collectors.toSet()).size();
        }

        /**
         * @return the number of chromosomes in this run
         */
        public int getChromosomeCount() {
            return this.strains.keySet().stream().map(x -> toChromosome(x)).collect(Collectors.toSet()).size();
        }

        /**
         * @return the chromosome portion of a strain
         */
        protected String toChromosome(String strain) {
            return RegExUtils.replaceFirst(strain, INSERT_PART, NULL_INSERT);
        }

        /**
         * @return a stream of the constructed strains in a strain set
         *
         * @param strains	set of strains to use
         */
        protected Stream<String> getConstructed(Set<String> strains) {
            return strains.stream().filter(x -> SampleId.isConstructed(x));
        }

        /**
         * @return the number of constructed strains in this run with production over the cutoff
         *
         * @param cutoff	cutoff of interest
         */
        public int getConstructedStrainHighCount(double cutoff) {
            return (int) this.strains.entrySet().stream()
                    .filter(x -> SampleId.isConstructed(x.getKey()) && x.getValue() >= cutoff).count();
        }

        /**
         * @return the number of constructed strains new to this run with production over the cutoff
         *
         * @param cutoff	cutoff of interest
         */
        public int getNewConstructedStrainHighCount(double cutoff) {
            return (int) this.strains1.entrySet().stream()
                    .filter(x -> SampleId.isConstructed(x.getKey()) && x.getValue() >= cutoff).count();
        }

        /**
         * @return the maximum production from a constructed strain
         */
        public double getMaxProdConstructed() {
            return this.maxProdConstructed;
        }

        /**
         * @return the maximum production from a control strain
         */
        public double getMaxProdControl() {
            return this.maxProdControl;
        }

        /**
         * @return the total number of predictions used to build the run
         */
        public int getPredFileSize() {
            return this.totalPredictions;
        }

        /**
         * @return the total number of predictions used to build the run that were above a cutoff
         */
        public int getHighFileSize() {
            return this.highPredictions;
        }

        /**
         * @return the number of samples with production higher than the cutoff
         *
         * @param cutoff	cutoff level (must be one of the predefined cutoffs)
         */
        public int getCutoffCount(double cutoff) {
            return this.cutoffCounts.getCount(cutoff);
        }

    }

    /**
     * This class is used to sort the components so that they make a more readable surface plot.
     */
    protected class ComponentSorter implements Comparator<String> {

        /** hash of component IDs to mean of the maximum */
        private Map<String, Double> ratingMap;

        /**
         * Create the map that we will use to sort the components.
         */
        protected ComponentSorter() {
            this.ratingMap = new HashMap<String, Double>(BigRunProcessor.this.pairMap.keySet().size() * 4 / 3 + 1);
            for (Map.Entry<String, Map<String, DescriptiveStatistics>> pairEntry : BigRunProcessor.this.pairMap.entrySet()) {
                var comp = pairEntry.getKey();
                var dMap = pairEntry.getValue();
                // Get the mean of each pair value for the source component.
                double value = dMap.values().stream().mapToDouble(x -> BigRunProcessor.this.getValue(x))
                        .filter(x -> x > 0).average().orElse(0.0);
                this.ratingMap.put(comp, value);
            }
        }

        @Override
        public int compare(String o1, String o2) {
            return Double.compare(this.ratingMap.getOrDefault(o1, 0.0), this.ratingMap.getOrDefault(o2, 0.0));
        }

    }

    // METHODS

    @Override
    protected void setDefaults() {
        this.scoreList = "1.2,2.0,4.0";
        this.choiceFile = null;
    }

    @Override
    protected boolean validateParms() throws IOException, ParseFailureException {
        // Create the score list.
        this.cutoffs = new FloatList(this.scoreList);
        // Check the input files.
        if (! this.bigProdFile.canRead())
            throw new FileNotFoundException("Production file " + this.bigProdFile + " is not found or unreadable.");
        if (! this.runFile.canRead())
            throw new FileNotFoundException("Run control file " + this.runFile + " is not found or unreadable.");
        // Check the choice file and create the sample formatter.
        if (this.choiceFile == null)
            this.choiceFile = new File(this.runFile.getParentFile(), "choices.tbl");
        if (! this.choiceFile.canRead())
            throw new FileNotFoundException("Choices file " + this.choiceFile + " is not found or unreadable.");
        log.info("Initializing xmatrix formatter.");
        this.formatter = new ThrSampleFormatter();
        this.formatter.setupChoices(this.choiceFile);
        return true;
    }

    @Override
    protected void runCommand() throws Exception {
        // Set up the map of count maps, one per cutoff.
        this.countMapMap = new TreeMap<Double, CountMap<String>>();
        this.countMapMap.put(0.0, new CountMap<String>());
        for (Double val : this.cutoffs)
            this.countMapMap.put(val, new CountMap<String>());
        this.countMapTotals = new CountMap<Double>();
        // Set up the pairing map.
        this.pairMap = new TreeMap<String, Map<String, DescriptiveStatistics>>();
        // Set up the counting sets.
        this.constructedStrainSet = new HashSet<String>(1000);
        // Set up the experiment counters.
        this.errors = new DescriptiveStatistics();
        this.experimentCount = 0;
        this.tryCounts = new CountMap<Integer>();
        this.outlierCount = 0;
        this.goodSamples = 0;
        this.badSamples = 0;
        // Set up the files.
        log.info("Processing control file.");
        this.processControlFile();
        log.info("Creating Excel workbook.");
        try (CustomWorkbook workbook = CustomWorkbook.create(this.outFile);
                TabbedLineReader bigProdStream = new TabbedLineReader(this.bigProdFile)) {
            // We have a workbook sheet for the master table and one for each run.
            var mainSheet = workbook.new Sheet("full_report", true);
            var sheetMap = new TreeMap<String, CustomWorkbook.Sheet>();
            for (RunDescriptor run : this.runs)
                sheetMap.put(run.getName(), workbook.new Sheet(run.getName() + "_report", true));
            // Put an entry for the main sheet in the map so we have them all together.
            sheetMap.put("(main)", mainSheet);
            //  Finally, a sheet for the x-matrix.
            var matrixSheet = workbook.new Sheet("xmatrix", true);
            // Set the precision to 4.
            workbook.setPrecision(4);
            // Set the headers for the production tables.
            var headers1 = this.computeTableHeaders();
            sheetMap.values().stream().forEach(x -> x.setHeaders(headers1));
            matrixSheet.setHeaders(this.computeMatrixHeaders());
            // Compute the input column indices.
            int numCol = bigProdStream.findField("num");
            int oldStrainCol = bigProdStream.findField("old_strain");
            int sampleCol = bigProdStream.findField("sample");
            int prodCol = bigProdStream.findField("thr_production");
            int densityCol = bigProdStream.findField("density");
            int badCol = bigProdStream.findField("bad");
            int originsCol = bigProdStream.findField("origins");
            int rawProdCol = bigProdStream.findField("raw_productions");
            int rawDensCol = bigProdStream.findField("raw_densities");
            // Now loop through the input.
            int lineCount = 0;
            for (TabbedLineReader.Line line : bigProdStream) {
                // Get the sample ID.
                String sampleId = line.get(sampleCol);
                SampleId sample = new SampleId(sampleId);
                var parts = sample.getComponents();
                // Get the origin and production data.
                String origins = line.get(originsCol);
                String rawProductions = line.get(rawProdCol);
                double production = line.getDouble(prodCol);
                // We are about to update some counts.  Only do this for good samples.
                String badFlag = line.get(badCol);
                boolean badSample = badFlag.contentEquals("Y");
                if (badSample)
                    this.badSamples++;
                else {
                    this.goodSamples++;
                    // Update the threshold counts.
                    for (Map.Entry<Double, CountMap<String>> countMap : this.countMapMap.entrySet()) {
                        final Double cutoffKey = countMap.getKey();
                        if (cutoffKey <= production) {
                            var actualMap = countMap.getValue();
                            parts.stream().forEach(x -> actualMap.count(x));
                            this.countMapTotals.count(cutoffKey);
                        }
                    }
                    // Update the diversity counts.
                    parts.stream().forEach(x -> this.updateDiversity(parts, x, production));
                    // Remember the strain.
                    String strain = sample.toStrain();
                    if (SampleId.isConstructed(strain))
                        this.constructedStrainSet.add(strain);
                }
                // Compute the runs, remembering the first.
                int run1 = this.runs.length;
                BitSet runsUsed = new BitSet(this.runs.length);
                for (String origin : StringUtils.splitByWholeSeparator(origins, ", ")) {
                    try {
                        int runI = IntStream.range(0, this.runs.length)
                                .filter(i -> this.runs[i].isMatch(origin)).findFirst().getAsInt();
                        if (runI < run1) run1 = runI;
                        runsUsed.set(runI);
                    } catch (NoSuchElementException e) {
                        log.info("Could not find a run for origin \"{}\".", origin);
                    }
                }
                String firstRun;
                if (run1 >= this.runs.length) {
                    log.info("Could not find first run for sample {}: \"{}\"", sampleId, origins);
                    throw new IOException("Invalid sample " + sampleId + " has no first run.");
                } else {
                    firstRun = this.runs[run1].getName();
                    // Add this sample to its runs if it is a good sample.
                    if (! badSample) {
                        // We need to declare a final version of run1 for streaming.
                        final int i1 = run1;
                        IntStream.range(0, this.runs.length).filter(i -> runsUsed.get(i))
                            .forEach(i -> this.runs[i].addSample(sample, i == i1, production));
                    }
                }
                // Get the output sheet for the first run.
                var runSheet = sheetMap.get(firstRun);
                // Compute the max production from the raw productions, skipping the questionable ones.
                // Here we also count trials and outliers (failures).
                double maxProduction = 0.0;
                int tries = 0;
                int fails = 0;
                for (String prodString : StringUtils.split(rawProductions, ",")) {
                    if (prodString.startsWith("("))
                        fails++;
                    else {
                        double prod = Double.valueOf(prodString);
                        if (prod > maxProduction) maxProduction = prod;
                        tries++;
                        this.errors.addValue(Math.abs(prod - production));
                    }
                }
                // If this is a good sample, update the experiment counts.
                if (! badSample) {
                    this.experimentCount += tries + fails;
                    this.outlierCount += fails;
                    this.tryCounts.count(tries);
                }
                // Get the predictions.
                double[] preds = this.predMap.get(sample);
                // Now we have the major derived fields.  Create the production rows for this sample.
                // (One on the main sheet, one on the run sheet.)
                mainSheet.addRow();
                runSheet.addRow();
                // Process the initial cells of this row.
                int num = line.getInt(numCol);
                mainSheet.storeCell(num);
                runSheet.storeCell(num);
                String oldStrain = line.get(oldStrainCol);
                mainSheet.storeCell(oldStrain);
                runSheet.storeCell(oldStrain);
                mainSheet.storeCell(sampleId);
                runSheet.storeCell(sampleId);
                mainSheet.storeCell(firstRun);
                runSheet.storeCell(firstRun);
                mainSheet.storeCell(badFlag);
                runSheet.storeCell(badFlag);
                String constructed = (sample.isConstructed() ? "Y" : "");
                mainSheet.storeCell(constructed);
                runSheet.storeCell(constructed);
                mainSheet.storeCell(production);
                runSheet.storeCell(production);
                mainSheet.storeCell(maxProduction);
                runSheet.storeCell(maxProduction);
                // Store the predictions.  Note NaN means no prediction was made.  A prediction
                // for a first-run sample gets added to the first-run analyzer.
                for (int i = 0; i < this.runs.length; i++) {
                    if (this.runs[i].hasPredictions()) {
                        double pred = preds[i];
                        if (! Double.isFinite(pred)) {
                            mainSheet.storeBlankCell();
                            runSheet.storeBlankCell();
                        } else {
                            mainSheet.storeCell(pred);
                            runSheet.storeCell(pred);
                            this.runs[i].addPrediction(sample, pred, production, (i == run1));
                        }
                    }
                }
                // Now we store the density.  A value of NaN is output as blank.
                double density = line.getDouble(densityCol);
                if (! Double.isFinite(density)) {
                    mainSheet.storeBlankCell();
                    runSheet.storeBlankCell();
                } else {
                    mainSheet.storeCell(density);
                    runSheet.storeCell(density);
                }
                // The remaining columns are all strings.
                mainSheet.storeCell(origins);
                runSheet.storeCell(origins);
                mainSheet.storeCell(rawProductions);
                runSheet.storeCell(rawProductions);
                String rawDensities = line.get(rawDensCol);
                mainSheet.storeCell(rawDensities);
                runSheet.storeCell(rawDensities);
                // Now write out the xmatrix row.
                this.writeMatrixRow(matrixSheet, sample, density, production, maxProduction);
                // Record our progress.
                lineCount++;
                if (lineCount % 1000 == 0)  log.info("{} samples processed.", lineCount);
            }
            // Close up all the production sheets.
            log.info("Finishing production sheets.");
            for (CustomWorkbook.Sheet sheet : sheetMap.values()) {
                // Reformat the bad-flag column as a flag.
                sheet.reformatFlagColumn(4);
                // Fix the column widths.
                sheet.autoSizeColumns();
                // Close the sheet.
                sheet.close();
            }
            // Close the x-matrix sheet.
            matrixSheet.autoSizeColumns();
            matrixSheet.close();
            log.info("Big production summary sheets completed.");
            for (RunDescriptor run : this.runs) {
                if (run.hasPredictions()) {
                    // Here we have predictions to plot.  Create a sheet to hold them.
                    log.info("Creating prediction sheet for {}.", run.getName());
                    workbook.addSheet(run.getName() + "_pred", true);
                    List<String> headers = Arrays.asList("pred_level", "tp", "fp", "tn", "fn",
                            "sensitivity", "miss_rate", "fallout", "accuracy");
                    workbook.setHeaders(headers);
                    // Get the prediction levels.
                    double[] predLevels = run.getAllPredictions();
                    run.setMaxPred(predLevels[0]);
                    // We must also build a list of XY pairs that can be adapted to compute the AUC.
                    var roc = new TreeSet<PredictionAnalyzer.XY>();
                    // Loop through the prediction levels.
                    for (double predLevel : predLevels) {
                        // Compute the confusion matrix at this level.
                        var m = run.getMatrix(predLevel);
                        // Save the AUC pair.
                        roc.add(new PredictionAnalyzer.XY(m));
                        // Create the spreadsheet row.
                        workbook.addRow();
                        workbook.storeCell(predLevel);
                        workbook.storeCell(m.truePositiveCount());
                        workbook.storeCell(m.falsePositiveCount());
                        workbook.storeCell(m.trueNegativeCount());
                        workbook.storeCell(m.falseNegativeCount());
                        workbook.storeCell(m.sensitivity());
                        workbook.storeCell(m.missRatio());
                        workbook.storeCell(m.fallout());
                        workbook.storeCell(m.accuracy());
                    }
                    // Fix the column widths.
                    workbook.autoSizeColumns();
                    // Now we need to store the pred/prod columns for the first-run samples.  These
                    // are in columns past the right edge of the table, with a spacer in between.
                    int c0 = headers.size() + 1;
                    int c1 = c0 + 1;
                    workbook.storeCell(0, c0, "production");
                    workbook.storeCell(0, c1, "prediction");
                    int r = 1;
                    for (PredProd pair : run.analyzer1.getAllSamples()) {
                        workbook.storeCell(r, c0, pair.getProduction(), CustomWorkbook.Num.FRACTION);
                        workbook.storeCell(r, c1, pair.getPrediction(), CustomWorkbook.Num.FRACTION);
                        r++;
                    }
                    // Compute and save the AUC.  We use a simple trapezoidal rule.
                    var iter = roc.iterator();
                    double auc = 0.0;
                    if (iter.hasNext()) {
                        var prev = iter.next();
                        while (iter.hasNext()) {
                            var curr = iter.next();
                            auc += curr.areaFrom(prev);
                            prev = curr;
                        }
                    }
                    run.setAuc(auc);
                }
            }
            // Now create the prediction summary sheet.
            log.info("Creating run summaries.");
            workbook.addSheet("performance", true);
            List<String> headers = this.computeRunHeaders();
            workbook.setHeaders(headers);
            final int n = this.cutoffs.size();
            // Now for each value we want to display, we show it for each run.
            this.newPerformanceRow(workbook, "tot_size", x -> workbook.storeCell(x.getRunSize()),
                    "Number of samples run.");
            this.newPerformanceRow(workbook, "new_size", x -> workbook.storeCell(x.getNewSize()),
                    "Number of samples new to this run.");
            this.newPerformanceRow(workbook, "max_prod_all", x -> workbook.storeCell(x.getMaxProdAll()),
                    "Maximum production output.");
            this.newPerformanceRow(workbook, "max_prod_constructed", x -> workbook.storeCell(x.getMaxProdConstructed()),
                    "Maximum production output for a constructed strain.");
            this.newPerformanceRow(workbook, "max_prod_control", x -> workbook.storeCell(x.getMaxProdControl()),
                    "Maximum production output for a control strain.");
            this.newPerformanceRow(workbook, "tot_strains", x -> workbook.storeCell(x.getStrainCount()),
                    "Number of strains run.");
            this.newPerformanceRow(workbook, "new_strains", x -> workbook.storeCell(x.getNewStrainCount()),
                    "Number of strains new to this run.");
            this.newPerformanceRow(workbook, "tot_constructed",
                    x -> workbook.storeCell(x.getConstructedStrainCount()),
                    "Number of constructed strains in this run.");
            this.newPerformanceRow(workbook, "new_constructed",
                    x -> workbook.storeCell(x.getNewConstructedStrainCount()),
                    "Number of constructed strains new to this run.");
            this.newPerformanceRow(workbook, "cons_chromosome",
                    x -> workbook.storeCell(x.getConstructedChromosomeCount()),
                    "Number of distinct constructed chromosomes in this run.");
            this.newPerformanceRow(workbook, "tot_chromosome",
                    x -> workbook.storeCell(x.getChromosomeCount()),
                    "Number of distinct chromosomes in this run.");
            // Now we have a bunch of things that only apply if we have predictions.
            this.newPredictionRow(workbook, "predictions_computed", x -> workbook.storeCell(x.getPredFileSize()),
                    "Total number of predictions computed in virtual space to build run.");
            this.newPredictionRow(workbook, "high_predictions_computed", x -> workbook.storeCell(x.getHighFileSize()),
                    String.format("Total number of predictions in virtual space >= %1.2f.", this.cutoffs.get(0)));
            this.newPredictionRow(workbook, "tot_predictions", x -> workbook.storeCell(x.size()),
                    "Number of samples with predicted values from the model used to create the run.");
            this.newPredictionRow(workbook, "new_predictions", x -> workbook.storeCell(x.getAnalyzer1().size()),
                    "Number of samples with predicted values new to this run.");
            this.newPredictionRow(workbook, "max_prediction", x -> workbook.storeCell(x.getMaxPred()),
                    "Maximum prediction from the model used to create the run.");
            this.newPredictionRow(workbook, "AUC", x -> workbook.storeCell(x.getAuc()),
                    "Area-under-curve for classification by production level of samples new to the run.");
            this.newPredictionRow(workbook, "Pearson", x -> workbook.storeCell(x.getPearson()),
                    "Pearson correlation for predicted and actual production levels in samples new to the run.");
            this.newPredictionRow(workbook, "MAE", x -> workbook.storeCell(x.analyzer1.getMAE()),
                    "Mean absolute error for predictions in samples new to the run.");
            for (int i = 0; i < n; i++) {
                double cutoff = this.cutoffs.get(i);
                // Start with MAE numbers.
                this.newPredictionRow(workbook, String.format("MAE >= %1.2f", cutoff),
                        x -> workbook.storeCell(x.analyzer.getHighMAE(cutoff)),
                        String.format("Mean Absolute Error for samples with production >= %1.2f.", cutoff));
                this.newPredictionRow(workbook, String.format("MAE < %1.2f", cutoff),
                        x -> workbook.storeCell(x.analyzer.getLowMAE(cutoff)),
                        String.format("Mean Absolute Error for samples with production < %1.2f.", cutoff));
                // Next we have some constructed-strain metrics.
                this.newPerformanceRow(workbook, String.format("high_constructed_%1.2f", cutoff),
                        x -> workbook.storeCell(x.getConstructedStrainHighCount(cutoff)),
                        String.format("Number of constructed strains in this run with production >= %1.2f.", cutoff));
                this.newPerformanceRow(workbook, String.format("new_high_constructed_%1.2f", cutoff),
                        x -> workbook.storeCell(x.getNewConstructedStrainHighCount(cutoff)),
                        String.format("Number of constructed strains new to this run with production >= %1.2f.", cutoff));
                this.newPerformanceRow(workbook, String.format("high_samples_%1.2f", cutoff),
                        x -> workbook.storeCell(x.getCutoffCount(cutoff)),
                        String.format("Number of constructed samples with production >= %1.2f.", cutoff));
                // We will use these arrays to store a confusion matrix for each run that has predictions.
                PredictionAnalyzer.Matrix[] mats = new PredictionAnalyzer.Matrix[this.runs.length];
                for (int runI = 0; runI < this.runs.length; runI++) {
                    var run = this.runs[runI];
                    if (! run.hasPredictions())
                        mats[runI] = null;
                    else
                        mats[runI] = run.getAnalyzer1().getMatrix(cutoff);
                }
                // Now produce all the classification metrics for each run.
                this.newCutoffRow(workbook, cutoff, mats, "predicted_%1.2f",
                        x -> workbook.storeCell(x.predictedCount()),
                        "Number of samples new to this run predicted positive using cutoff %1.2f");
                this.newCutoffRow(workbook, cutoff, mats, "actual_%1.2f",
                        x -> workbook.storeCell(x.actualCount()),
                        "Number of samples new to this run producing more than cutoff %1.2f");
                this.newCutoffRow(workbook, cutoff, mats, "true_positive_%1.2f",
                        x -> workbook.storeCell(x.truePositiveCount()),
                        "Number of true positive results in samples new to the run using cutoff %1.2f.");
                this.newCutoffRow(workbook, cutoff, mats, "false_positive_%1.2f",
                        x -> workbook.storeCell(x.falsePositiveCount()),
                        "Number of false positive results in samples new to the run using cutoff %1.2f.");
                this.newCutoffRow(workbook, cutoff, mats, "true_negative_%1.2f",
                        x -> workbook.storeCell(x.trueNegativeCount()),
                        "Number of true negative results in samples new to the run using cutoff %1.2f.");
                this.newCutoffRow(workbook, cutoff, mats, "false_negative_%1.2f",
                        x -> workbook.storeCell(x.falseNegativeCount()),
                        "Number of false negative results in samples new to the run using cutoff %1.2f.");
                this.newCutoffRow(workbook, cutoff, mats, "precision_%1.2f",
                        x -> workbook.storeCell(x.precision()),
                        "Chance of a positive prediction being a positive result using cutoff %1.2f.");
                this.newCutoffRow(workbook, cutoff, mats, "sensitivity_%1.2f",
                        x -> workbook.storeCell(x.sensitivity()),
                        "Chance of a positive result being predicted positive using cutoff %1.2f.");
                this.newCutoffRow(workbook, cutoff, mats, "accuracy_%1.2f",
                        x -> workbook.storeCell(x.accuracy()),
                        "Accuracy of predictions for samples new to the run using classification cutoff %1.2f.");
                this.newCutoffRow(workbook, cutoff, mats, "fallout_%1.2f",
                        x -> workbook.storeCell(x.fallout()),
                        "Chance of a negative result being predicted positive using cutoff %1.2f");
                this.newCutoffRow(workbook, cutoff, mats, "F1score_%1.2f",
                        x -> workbook.storeCell(x.f1score()),
                        "Combined precision / recall rating");
                this.newCutoffRow(workbook, cutoff, mats, "MCC_%1.2f",
                        x -> workbook.storeCell(x.mcc()),
                        "Classification rating:  1 = perfect, 0 = random, -1 = always wrong");
            }
            workbook.autoSizeColumns();
            // Next, we have the component count sheet.  We have a column for the component titles, a column
            // for each cutoff, and a diversity-count column for each component.  Each component is a row,
            // and the final row is for totals.
            log.info("Creating component count page.");
            workbook.addSheet("components", true);
            workbook.setHeaders(this.computeCountHeaders());
            // Now we need to build the component list.  This is the union of all the key sets.
            var components = this.pairMap.keySet();
            // We are ready to build the component count page.  Loop through the components.  Each is a row.
            for (String component : components) {
                workbook.addRow();
                workbook.storeCell(component);
                // Extract the base count for percentages.
                var base = this.countMapMap.get(0.0).getCount(component);
                for (Map.Entry<Double, CountMap<String>> countMapEntry : this.countMapMap.entrySet()) {
                    var count = countMapEntry.getValue().getCount(component);
                    workbook.storeCell(count);
                    if (countMapEntry.getKey() > 0.0) {
                        if (base == 0)
                            workbook.storeBlankCell();
                        else {
                            double pct = count * 100.0 / base;
                            workbook.storeCell(pct);
                        }
                    }
                }
                var dMap = this.pairMap.get(component);
                for (String comp2 : components) {
                    DescriptiveStatistics pairStats = dMap.get(comp2);
                    if (pairStats == null || pairStats.getN() == 0)
                        workbook.storeBlankCell();
                    else
                        workbook.storeCell((int) pairStats.getN());
                }
            }
            workbook.autoSizeColumns();
            // This next sheet contains a summary value for each component combination.  The result can be
            // used for a heat map or surface plot.  Currently, the summary value is the maximum.
            log.info("Creating component-pairing page.");
            List<String> sortedComponents = new ArrayList<String>(components);
            Collections.sort(sortedComponents, this.new ComponentSorter());
            workbook.addSheet("comp_pairs", true);
            headers = new ArrayList<String>(sortedComponents.size() + 1);
            headers.add("component");
            headers.addAll(sortedComponents);
            workbook.setHeaders(headers);
            // Now we create one row per component.
            for (String comp : sortedComponents) {
                var dMap = this.pairMap.get(comp);
                workbook.addRow();
                workbook.storeCell(comp);
                for (String comp2 : sortedComponents) {
                    DescriptiveStatistics stats = dMap.get(comp2);
                    if (stats == null)
                        workbook.storeBlankCell();
                    else
                        workbook.storeCell(getValue(stats));
                }
            }
            workbook.autoSizeColumns();
            // The final sheet contains global statistics.
            log.info("Creating global statistics page.");
            workbook.addSheet("Good Samples", true);
            workbook.setHeaders(Arrays.asList("Statistic", "Value"));
            workbook.addRow();
            workbook.storeCell("Number of good samples");
            workbook.storeCell(this.goodSamples);
            workbook.addRow();
            workbook.storeCell("Number of bad samples");
            workbook.storeCell(this.badSamples);
            workbook.addRow();
            workbook.storeCell("Total number of experiments");
            workbook.storeCell(this.experimentCount);
            workbook.addRow();
            workbook.storeCell("Total number of outliers");
            workbook.storeCell(this.outlierCount);
            workbook.addRow();
            workbook.storeCell("Mean absolute error in experiments");
            workbook.storeCell(this.errors.getMean());
            workbook.addRow();
            workbook.storeCell("Standard deviation of absolute error in experiments");
            workbook.storeCell(this.errors.getStandardDeviation());
            workbook.addRow();
            workbook.storeCell("Number of constructed strains");
            workbook.storeCell(this.constructedStrainSet.size());
            // Go through the try counts in numerical order.
            final var tries = this.tryCounts.keys();
            for (var tryCount : tries) {
                workbook.addRow();
                workbook.storeCell(String.format("Samples with %2d good tries", tryCount));
                workbook.storeCell(this.tryCounts.getCount(tryCount));
            }
            // Go through the cutoff counts in numerical order.
            for (var cutoff : this.cutoffs) {
                workbook.addRow();
                workbook.storeCell(String.format("Samples with production >= %1.2f", cutoff));
                workbook.storeCell(this.countMapTotals.getCount(cutoff));
            }
            workbook.autoSizeColumns();

        }

    }

    /**
     * Fill in a new row in the performance sheet.
     *
     * @param workbook		output workbook, positioned on the performance sheet
     * @param title			title for this row
     * @param action		action used to fill the row from the run descriptor
     * @param desc 			description of the value
     */
    private void newPerformanceRow(CustomWorkbook workbook, String title, Consumer<RunDescriptor> action, String desc) {
        workbook.addRow();
        workbook.storeCell(title);
        Arrays.stream(this.runs).forEach(action);
        workbook.storeCell(desc);
    }

    /**
     * Fill in a new prediction-based row in the performance sheet.
     *
     * @param workbook		output workbook, positioned on the performance sheet
     * @param title			title for this row
     * @param action		action used to fill the row from the run descriptor
     * @param desc 			description of the value
     */
    private void newPredictionRow(CustomWorkbook workbook, String title, Consumer<RunDescriptor> action,
            String desc) {
        // What we do here is skip output for any run that doesn't have predictions.
        this.newPerformanceRow(workbook, title,
                x -> { if (x.hasPredictions()) action.accept(x); else workbook.storeBlankCell(); },
                desc);
    }

    /**
     * Produce a row of cutoff-related pseudo-classification metrics.
     *
     * @param workbook		output workbook, positioned on the performance sheet
     * @param cutoff		cutoff level for pseudo-classification
     * @param mats			array of confusion matrices for the runs
     * @param label			label for the row, with a format substitution position for the cutoff
     * @param action		action to take for each matrix
     * @param desc			description of the value
     */
    private void newCutoffRow(CustomWorkbook workbook, double cutoff, PredictionAnalyzer.Matrix[] mats,
            String label, Consumer<PredictionAnalyzer.Matrix> action, String desc) {
        workbook.addRow();
        workbook.storeCell(String.format(label, cutoff));
        for (int i = 0; i < mats.length; i++) {
            if (mats[i] == null)
                workbook.storeBlankCell();
            else
                action.accept(mats[i]);
        }
        workbook.storeCell(String.format(desc, cutoff));
    }

    /**
     * @return the useful value from the diversity statistics object
     *
     * @param stats		diversity statistics object for one component pair
     */
    protected double getValue(DescriptiveStatistics stats) {
        return stats.getMax();
    }

    /**
     * This method updates the diversity map.  It is called for each component of a sample, and its
     * purpose is to count all of the other components that appear with the specified component.
     *
     * @param parts		component parts of the sample
     * @param comp		component to count
     * @param prod		production value of the sample
     */
    private void updateDiversity(Collection<String> parts, String comp, double prod) {
        // Get the diversity counts for this component.
        Map<String, DescriptiveStatistics> dMap = this.pairMap.computeIfAbsent(comp, x -> new HashMap<String, DescriptiveStatistics>());
        // Count all the other components that appear with it.
        parts.stream().filter(x -> ! x.contentEquals(comp)).forEach(x -> this.recordPair(dMap, x, prod));
    }

    /**
     * Record a pair instance in a pairing sub-map.  The submap represents a primary component and contains a
     * DescriptiveStatistics object for each other possible paired component.
     *
     * @param dMap		pairing sub-map for the primary component
     * @param comp		secondary component
     * @param prod		production output value
     */
    private void recordPair(Map<String, DescriptiveStatistics> dMap, String comp, double prod) {
        var stats = dMap.computeIfAbsent(comp, x -> new DescriptiveStatistics());
        stats.addValue(prod);
    }

    /**
     * Add a row for this sample to the x-matrix sheet.
     *
     * @param sheet				output sheet
     * @param sample			sample identifier
     * @param density			optical density
     * @param production		production
     * @param maxProduction		maximum production
     */
    private void writeMatrixRow(CustomWorkbook.Sheet sheet, SampleId sample, double density, double production,
            double maxProduction) {
        sheet.addRow();
        sheet.storeCell(sample.toString());
        double[] parms = this.formatter.parseSample(sample);
        Arrays.stream(parms).forEach(x -> sheet.storeCell(x, CustomWorkbook.Num.ML));
        if (! Double.isFinite(density))
            sheet.storeBlankCell();
        else
            sheet.storeCell(density);
        sheet.storeCell(production);
        sheet.storeCell(maxProduction);
    }

    /**
     * @return the headers for the x-matrix sheet.
     */
    private List<String> computeMatrixHeaders() {
        String[] cols = this.formatter.getTitles();
        var retVal = new ArrayList<String>(cols.length + 3);
        retVal.add("sample");
        Arrays.stream(cols).forEach(x -> retVal.add(x));
        retVal.add("density");
        retVal.add("mean_production");
        retVal.add("max_production");
        return retVal;
    }

    /**
     * @return the list of headers for the performance spreadsheet table
     */
    private List<String> computeRunHeaders() {
        List<String> retVal = new ArrayList<String>(this.runs.length + 1);
        retVal.add("statistic");
        for (var run : this.runs)
            retVal.add(run.getName());
        retVal.add("Detailed description");
        return retVal;
    }

    /**
     * @return the list of headers for the component spreadsheet table
     */
    private List<String> computeCountHeaders() {
        final int n = this.cutoffs.size();
        List<String> retVal = new ArrayList<String>(2 + n);
        retVal.add("component");
        for (double val : this.countMapMap.keySet()) {
            retVal.add(String.format("cutoff_%2.1f", val));
            if (val > 0.0)
                retVal.add(String.format("pct_%2.1f", val));
        }
        for (String comp : this.pairMap.keySet())
            retVal.add(comp);
        return retVal;
    }

    /**
     * @return the list of headers for the big production spreadsheet table
     */
    private List<String> computeTableHeaders() {
        // Get the prediction headers.
        List<String> predHeads = Arrays.stream(this.runs).filter(x -> x.hasPredictions())
                .map(x -> "pred_" + x.getName()).collect(Collectors.toList());
        List<String> retVal = new ArrayList<String>(11 + predHeads.size());
        retVal.add("num");
        retVal.add("old_strain");
        retVal.add("sample");
        retVal.add("first_run");
        retVal.add("bad");
        retVal.add("constructed");
        retVal.add("thr_production");
        retVal.add("max_production");
        retVal.addAll(predHeads);
        retVal.add("density");
        retVal.add("origins");
        retVal.add("raw_productions");
        retVal.add("raw_densitites");
        return retVal;
    }

    /**
     * Read the control file, set up the run descriptors, and load the predictions into memory.
     */
    private void processControlFile() throws IOException {
        // Build the list of run descriptors.
        try (TabbedLineReader runStream = new TabbedLineReader(this.runFile)) {
            this.runs = runStream.stream().map(x -> new RunDescriptor(x)).toArray(RunDescriptor[]::new);
        }
        // Read the big production table to create a skeleton prediction map.
        log.info("Getting sample list from {}.", this.bigProdFile);
        this.predMap = new HashMap<SampleId, double[]>(1900);
        try (TabbedLineReader bigStream = new TabbedLineReader(this.bigProdFile)) {
            int sampleCol = bigStream.findField("sample");
            for (TabbedLineReader.Line line : bigStream) {
                SampleId sample = new SampleId(line.get(sampleCol));
                double[] preds = new double[this.runs.length];
                for (int i = 0; i < this.runs.length; i++) preds[i] = Double.NaN;
                this.predMap.put(sample, preds);
            }
            log.info("{} samples found in file.", this.predMap.size());
        }
        // Now build the prediction tables.
        for (int i = 0; i < this.runs.length; i++)
            this.runs[i].storePredictions(i);
    }

}
