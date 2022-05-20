/**
 *
 */
package org.theseed.threonine;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.stat.correlation.PearsonsCorrelation;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.counters.CountMap;
import org.theseed.excel.CustomWorkbook;
import org.theseed.io.TabbedLineReader;
import org.theseed.reports.PredProd;
import org.theseed.reports.PredictionAnalyzer;
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
 * --scores	list of cutoff scores to use for enrichment computation (default 1.2, 2.0, 4.0)
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
    /** cutoff counts */
    private CountMap<Double> countMapTotals;
    /** pearson correlation engine */
    private final PearsonsCorrelation computer = new PearsonsCorrelation();


    // COMMAND-LINE OPTIONS

    /** list of cutoff scores for enrichment computation */
    @Option(name = "--scores", metaVar = "1.2,2.0", usage = "comma-delimited list of cutoff scores to test")
    private String scoreList;

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
        /** first-run prediction analyzer */
        private PredictionAnalyzer analyzer1;
        /** area under the curve */
        private double auc;
        /** maximum prediction */
        private double maxPred;
        /** maximum production */
        private double maxProdAll;
        /** number of samples for this run */
        private int runSize;

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
            this.runSize = 0;
            this.maxProdAll = 0;
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
                try (var predStream = new TabbedLineReader(this.predFile)) {
                    for (TabbedLineReader.Line line : predStream) {
                        SampleId sample = new SampleId(line.get(0));
                        double pred = line.getDouble(1);
                        double[] preds = BigRunProcessor.this.predMap.get(sample);
                        if (preds != null) {
                            preds[i] = pred;
                            predCount++;
                        }
                        lineCount++;
                    }
                    log.info("{} predictions retrieved from {} lines.", predCount, lineCount);
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
         * @param pred			predicted value for this run
         * @param production	actual value of sample
         */
        public void addPrediction(double pred, double production) {
            this.analyzer.add(pred, production);
        }

        /**
         * Add a prediction/production pair to this run's first-run analysis.
         *
         * @param pred			predicted value for this run
         * @param production	actual value of sample
         */
        public void addPrediction1(double pred, double production) {
            this.analyzer1.add(pred, production);
        }

        /**
         * @return a sorted array of the prediction levels for this run
         */
        public double[] getAllPredictions() {
            return this.analyzer.getAllPredictions();
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
         * Increment the run size.
         */
        public void count() {
            this.runSize++;
        }

        /**
         * @return the run size
         */
        public int getRunSize() {
            return this.runSize;
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
         * Update the maximum production for the run.
         *
         * @param prod		production level
         */
        public void setMaxProdAll(double prod) {
            if (prod > this.maxProdAll)
                this.maxProdAll = prod;
        }

    }

    @Override
    protected void setDefaults() {
        this.scoreList = "1.2,2.0,4.0";
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
        // Set up the files.
        log.info("Processing control file.");
        this.processControlFile();
        log.info("Creating Excel workbook.");
        try (CustomWorkbook workbook = CustomWorkbook.create(this.outFile);
                TabbedLineReader bigProdStream = new TabbedLineReader(this.bigProdFile)) {
            // Create the output sheet for the master table.
            workbook.addSheet("production_report", true);
            // Set the precision to 4.
            workbook.setPrecision(4);
            // Compute the prediction headers.
            workbook.setHeaders(this.computeTableHeaders());
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
            for (TabbedLineReader.Line line : bigProdStream) {
                // Get the sample ID.
                String sampleId = line.get(sampleCol);
                SampleId sample = new SampleId(sampleId);
                // Update the counts.
                double production = line.getDouble(prodCol);
                for (Map.Entry<Double, CountMap<String>> countMap : this.countMapMap.entrySet()) {
                    final Double cutoffKey = countMap.getKey();
                    if (cutoffKey <= production) {
                        sample.countParts(countMap.getValue());
                        this.countMapTotals.count(cutoffKey);
                    }
                }
                // Get the origin and raw-production strings.
                String origins = line.get(originsCol);
                String rawProductions = line.get(rawProdCol);
                // Compute the first run.
                int run1 = this.runs.length;
                for (String origin : StringUtils.splitByWholeSeparator(origins, ", ")) {
                    try {
                        int runI = IntStream.range(0, this.runs.length)
                                .filter(i -> this.runs[i].isMatch(origin)).findFirst().getAsInt();
                        if (runI < run1) run1 = runI;
                    } catch (NoSuchElementException e) {
                        log.info("Could not find a run for origin \"{}\".", origin);
                    }
                }
                String firstRun;
                if (run1 >= this.runs.length) {
                    log.info("Could not find first run for sample {}: \"{}\"", sampleId, origins);
                    firstRun = "";
                } else {
                    firstRun = this.runs[run1].getName();
                    this.runs[run1].count();
                    this.runs[run1].setMaxProdAll(production);
                }
                // Compute the max production from the raw productions, skipping the questionable ones.
                double maxProduction = 0.0;
                for (String prodString : StringUtils.split(rawProductions, ",")) {
                    if (! prodString.startsWith("(")) {
                        double prod = Double.valueOf(prodString);
                        if (prod > maxProduction) maxProduction = prod;
                    }
                }
                // Get the predictions.
                double[] preds = this.predMap.get(sample);
                // Now we have the major derived fields.  Create the row for this sample.
                workbook.addRow();
                // Process the initial cells of this row.
                int num = line.getInt(numCol);
                workbook.storeCell(num);
                String oldStrain = line.get(oldStrainCol);
                workbook.storeCell(oldStrain);
                workbook.storeCell(sampleId);
                workbook.storeCell(firstRun);
                String badFlag = line.get(badCol);
                workbook.storeCell(badFlag);
                workbook.storeCell(production);
                workbook.storeCell(maxProduction);
                // Store the predictions.  Note NaN means no prediction was made.  A prediction
                // for a first-run sample gets special treatment.
                for (int i = 0; i < this.runs.length; i++) {
                    if (this.runs[i].hasPredictions()) {
                        double pred = preds[i];
                        if (! Double.isFinite(pred))
                            workbook.storeBlankCell();
                        else {
                            workbook.storeCell(pred);
                            this.runs[i].addPrediction(pred, production);
                            if (i == run1)
                                this.runs[i].addPrediction1(pred, production);
                        }
                    }
                }
                // Now we store the density.  A value of NaN is output as blank.
                double density = line.getDouble(densityCol);
                if (! Double.isFinite(density))
                    workbook.storeBlankCell();
                else
                    workbook.storeCell(density);
                // The remaining columns are all strings.
                workbook.storeCell(origins);
                workbook.storeCell(rawProductions);
                String rawDensities = line.get(rawDensCol);
                workbook.storeCell(rawDensities);
            }
            // Reformat the bad-flag column as a flag.
            workbook.reformatFlagColumn(4);
            // Fix the column widths.
            workbook.autoSizeColumns();
            log.info("Big production summary sheet completed.");
            for (RunDescriptor run : this.runs) {
                if (run.hasPredictions()) {
                    // Here we have predictions to plot.  Create a sheet to hold them.
                    log.info("Creating prediction sheet for {}.", run.getName());
                    workbook.addSheet(run.getName(), true);
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
            // Loop through the runs.
            for (RunDescriptor run : this.runs) {
                workbook.addRow();
                workbook.storeCell(run.getName());
                workbook.storeCell(run.getRunSize());
                workbook.storeCell(run.getMaxProdAll());
                if (! run.hasPredictions()) {
                    // No predictions:  blank the row.
                    IntStream.range(3, headers.size()).forEach(i -> workbook.storeBlankCell());
                } else {
                    // We have predictions.  Get the first-run analyzer.
                    var analyzer1 = run.getAnalyzer1();
                    // Add the metrics.
                    workbook.storeCell(run.size());
                    workbook.storeCell(analyzer1.size());
                    workbook.storeCell(run.getMaxPred());
                    workbook.storeCell(analyzer1.getMaxProd());
                    workbook.storeCell(run.getAuc());
                    workbook.storeCell(run.getPearson());
                    // Compute the prediction success rates.
                    for (int i = 0; i < n; i++) {
                        var m = analyzer1.getMatrix(this.cutoffs.get(i));
                        workbook.storeCell(m.sensitivity());
                        workbook.storeCell(m.falsePositiveCount() + m.truePositiveCount());
                    }
                }
            }
            workbook.autoSizeColumns();
            // Finally, we have the component count sheet.  We have a column for the component titles and a column
            // for each cutoff.  Each component is a row, and the final row is for totals.
            log.info("Creating component count page.");
            workbook.addSheet("components", true);
            workbook.setHeaders(this.computeCountHeaders());
            // Now we need to build the component list.  This is the union of all the key sets.
            var components = new TreeSet<String>();
            this.countMapMap.values().forEach(x -> components.addAll(x.keys()));
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
            }
            // Finally, the total row.
            workbook.addRow();
            workbook.storeCell("TOTAL");
            for (Double cutoff : this.countMapMap.keySet()) {
                workbook.storeCell(this.countMapTotals.count(cutoff));
                if (cutoff > 0.0)
                    workbook.storeBlankCell();
            }
            workbook.autoSizeColumns();
        }
    }

    /**
     * @return the list of headers for the performance spreadsheet table
     */
    private List<String> computeRunHeaders() {
        final int n = this.cutoffs.size();
        List<String> retVal = new ArrayList<String>(7 + n);
        retVal.add("run");
        retVal.add("size");
        retVal.add("max_prod_all");
        retVal.add("predictions");
        retVal.add("predictions_run");
        retVal.add("max_pred");
        retVal.add("max_prod");
        retVal.add("AUC");
        retVal.add("Pearson");
        for (int i = 0; i < n; i++) {
            retVal.add(String.format("success_%2.1f", this.cutoffs.get(i)));
            retVal.add(String.format("predicted_%2.1f", this.cutoffs.get(i)));
        }
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
