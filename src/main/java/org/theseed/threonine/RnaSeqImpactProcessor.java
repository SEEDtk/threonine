/**
 *
 */
package org.theseed.threonine;

import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.kohsuke.args4j.Argument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.genome.Feature;
import org.theseed.genome.Genome;
import org.theseed.genome.SubsystemRow;
import org.theseed.io.TabbedLineReader;
import org.theseed.magic.MagicMap;
import org.theseed.subsystems.SubsystemRowDescriptor;
import org.theseed.utils.BaseProcessor;
import org.theseed.utils.ParseFailureException;

/**
 * This command creates an impact report.  The report combines data from several other reports to produce a picture
 * of the impact of different genes on the results of a random forest model.  A filter file contains a
 * list of features of particular interest, and these are marked.  A GTO is used to associate the features with
 * subsystems and determine the functional assignment.  The "corr.tbl" file in the model directory is used to determine
 * the correlation between expression level and threonine production.  Finally, the "impactN.tbl" files in the model directory
 * contain the impact on the final decision of each gene.  The output is sorted by impact.
 *
 * The positional parameters are the name of the model directory and the name of the reference genome file.
 *
 * The command-line options are as follows.
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 *
 * @author Bruce Parrello
 *
 */
public class RnaSeqImpactProcessor extends BaseProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(RnaSeqImpactProcessor.class);
    /** reference genome table */
    private Genome refGenome;
    /** subsystem ID map */
    private MagicMap<SubsystemRowDescriptor> subMap;
    /** map of peg numbers to gene descriptors */
    private Map<String, Gene> geneMap;
    /** correlation file name */
    private File corrFile;
    /** set of special genes */
    private Set<String> specials;
    /** log2(3) */
    private static final double MAX_IMPACT = Math.log(3.0) / Math.log(2.0);


    // COMMAND-LINE OPTIONS

    /** name of the model directory */
    @Argument(index = 0, metaVar = "modelDir", usage = "directory containing the RNA Seq classifier", required = true)
    private File modelDir;

    /** name of the reference genome file */
    @Argument(index = 1, metaVar = "refGenome.gto", usage = "reference-genome file", required = true)
    private File gtoFile;

    /**
     * This object describes all the data we keep about a gene.
     */
    private class Gene implements Comparable<Gene> {

        /** ID of the feature */
        private String figId;
        /** gene name */
        private String gene;
        /** functional assignment */
        private String function;
        /** comma-delimited list of iModulons (or empty string if none) */
        private boolean special;
        /** number of impact values found */
        private int impactCount;
        /** total of impact values found */
        private double impactTotal;
        /** pearson correlation to threonine output */
        private double corr;
        /** comma-delimited list of subsystem IDs (or empty string if none) */
        private String subs;

        /**
         * Create a gene descriptor from gene impact data.
         *
         * @param geneId	gene identifier (gene name, dot, peg number)
         */
        public Gene(String geneId) {
            String[] parts = StringUtils.split(geneId, ".");
            this.gene = parts[0];
            this.impactTotal = 0.0;
            this.impactCount = 0;
            // The FIG ID is computed from the peg number and the genome ID.
            String genomeId = RnaSeqImpactProcessor.this.refGenome.getId();
            this.figId = "fig|" + genomeId + ".peg." + parts[1];
            // Clear the other indicators.
            this.special = RnaSeqImpactProcessor.this.specials.contains(this.figId);
            this.corr = 0.0;
            // Get the function.
            Feature feat = RnaSeqImpactProcessor.this.refGenome.getFeature(this.figId);
            this.function = feat.getPegFunction();
            // Compute the subsystems.
            List<String> subIds = new ArrayList<String>();
            for (SubsystemRow row : feat.getSubsystemRows()) {
                SubsystemRowDescriptor sub = RnaSeqImpactProcessor.this.subMap.getByName(row.getName());
                if (sub == null)
                    sub = new SubsystemRowDescriptor(row, RnaSeqImpactProcessor.this.subMap);
                subIds.add(sub.getId());
            }
            this.subs = StringUtils.join(subIds, ",");
        }

        /**
         * Record an impact for this gene.
         *
         * @param impact	impact value to record; 0.0 means none
         */
        public void recordImpact(double impact) {
            if (impact > 0.0) {
                this.impactCount++;
                this.impactTotal += impact;
            }
        }

        /**
         * @return the output line for this gene
         */
        public String toLine() {
            String flag = (this.special ? "Y" : "");
            String dir = (this.corr >= 0.0 ? "+" : (this.corr == 0 ? "" : "-"));
            double impact = this.getImpact() * 100.0 / MAX_IMPACT;
            return String.format("%s\t%s\t%6.4f\t%s\t%d\t%s\t%s\t%s", this.figId, this.gene,
                    impact, dir, this.impactCount, this.function, flag,
                    this.subs);
        }

        /**
         * @return the mean computed impact
         */
        private double getImpact() {
            double retVal = 0.0;
            if (this.impactCount > 0) retVal = this.impactTotal / this.impactCount;
            return retVal;
        }

        /** output header line */
        private static final String HEADER_LINE = "fig_id\tgene\t%impact\tdir\tcount\tfunction\tspecial\tsubsystems";

        /**
         * Specify this gene's correlation to the threonine output.
         *
         * @param corr	the relevant correlation value
         */
        public void setCorr(double corr) {
            this.corr = corr;
        }

        /**
         * We sort from highest to lowest impact, then gene name.
         */
        @Override
        public int compareTo(Gene o) {
            int retVal = Double.compare(o.getImpact(), this.getImpact());
            if (retVal == 0)
                retVal = this.gene.compareTo(o.gene);
            if (retVal == 0)
                retVal = this.figId.compareTo(o.figId);
            return retVal;
        }

    }

    /**
     * File filter for finding impact files.
     */
    public static class ImpactFilter implements FileFilter {

        public static final Pattern IMPACT_NAME = Pattern.compile("impact\\d+.tbl");

        @Override
        public boolean accept(File pathname) {
            boolean retVal = false;
            if (! pathname.isDirectory()) {
                String name = pathname.getName();
                retVal = IMPACT_NAME.matcher(name).matches();
            }
            return retVal;
        }

    }

    @Override
    protected void setDefaults() {
    }

    @Override
    protected boolean validateParms() throws IOException, ParseFailureException {
        // Verify that the model directory is good.
        if (! this.modelDir.isDirectory())
            throw new FileNotFoundException("Model directory " + this.modelDir + " is not found or invalid.");
        this.corrFile = new File(this.modelDir, "corr.tbl");
        if (! this.corrFile.canRead())
            throw new FileNotFoundException("Correlation file " + this.corrFile + " is not found or unreadable.");
        // Verify that the special filter file exists.
        File specialFile = new File(this.modelDir, "special.tbl");
        if (! specialFile.exists())
            log.info("No special feature file found.  No features will be marked special.");
        else {
            this.specials = TabbedLineReader.readSet(specialFile, "1");
            log.info("{} special features read from {}.", this.specials.size(), specialFile);
        }
        // Load the reference genome.
        log.info("Loading reference genome from {}.", this.gtoFile);
        this.refGenome = new Genome(this.gtoFile);
        return true;
    }

    @Override
    protected void runCommand() throws Exception {
        // Create the subsystem map.
        this.subMap = new MagicMap<SubsystemRowDescriptor>(new SubsystemRowDescriptor());
        // Create the gene structures.
        this.geneMap = new HashMap<String, Gene>(1000);
        // Get the list of impact files.
        File[] impactFiles = this.getImpactFiles();
        // Fill the gene map from the impact files.
        for (File impactFile : impactFiles) {
            try (TabbedLineReader impactStream = new TabbedLineReader(impactFile)) {
                log.info("Reading impact data from {}.", impactFile);
                int gCount = 0;
                for (TabbedLineReader.Line line : impactStream) {
                    String colName = line.get(0);
                    double impact = line.getDouble(1);
                    String num = getPegNumber(colName);
                    Gene gene = this.geneMap.computeIfAbsent(num, g -> new Gene(colName));
                    gene.recordImpact(impact);
                    gCount++;
                }
                log.info("{} genes found in {}.", gCount, impactFile);
            }
        }
        // Add the pearson correlations.
        try (TabbedLineReader corrStream = new TabbedLineReader(this.corrFile)) {
            log.info("Reading correlation data from {}.", this.corrFile);
            int nameCol = corrStream.findField("col_name");
            int corrCol = corrStream.findField("p_correlation");
            int count = 0;
            for (TabbedLineReader.Line line : corrStream) {
                String colName = line.get(nameCol);
                // If this gene is interesting to us, it will be in the map.
                Gene geneDescriptor = this.geneMap.get(getPegNumber(colName));
                if (geneDescriptor != null) {
                    geneDescriptor.setCorr(line.getDouble(corrCol));
                    count++;
                }
            }
            log.info("{} correlations stored.", count);
        }
        // Sort the output and write it.
        List<Gene> geneList = new ArrayList<Gene>(this.geneMap.values());
        Collections.sort(geneList);
        try (PrintWriter writer = new PrintWriter(System.out)) {
            log.info("Writing output.");
            writer.println(Gene.HEADER_LINE);
            for (Gene geneDescriptor : geneList)
                writer.println(geneDescriptor.toLine());
        }
    }

    /**
     * @return the list of impact-related files in the model directory
     *
     * @throws FileNotFoundException
     */
    private File[] getImpactFiles() throws FileNotFoundException {
        File[] retVal = this.modelDir.listFiles(new ImpactFilter());
        if (retVal.length == 0) {
            // If there are no numbered impact files, we use the main one.
            File baseFile = new File(this.modelDir, "impact.tbl");
            if (! baseFile.exists())
                throw new FileNotFoundException("No impact file present in " + this.modelDir + ".");
            retVal = new File[] { baseFile };
        }
        return retVal;
    }

    /**
     * @return the peg number for a FIG ID or column name
     */
    public static String getPegNumber(String pegId) {
        return StringUtils.substringAfterLast(pegId, ".");
    }

}
