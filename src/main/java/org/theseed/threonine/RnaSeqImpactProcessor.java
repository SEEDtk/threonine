/**
 *
 */
package org.theseed.threonine;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * of the impact of different genes on the results of a random forest model.  The "groups.tbl" file is used to
 * associate genes with modulons and atomic regulons.  A GTO is used to associate them with subsystems and determine
 * the functional assignment.  The "corr.tbl" file in the model directory is used to determine the correlation
 * between expression level and threonine production.  Finally, the "impact.tbl" file in the model directory
 * contains the impact on the final decision of each gene.  The output is sorted by impact and only non-zero
 * impacts are included.
 *
 * The positional parameters are the name of the model directory, the name of the groups file, and the name of the reference genome file.
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
    /** ordered list of gene descriptors */
    private List<Gene> geneList;
    /** correlation file name */
    private File corrFile;
    /** impact file name */
    private File impactFile;
    /** output header line */
    private static final String HEADER_LINE = "fig_id\tgene\timpact\tcorrelation\tfunction\tregulon\tmodulons\tsubsystems";


    // COMMAND-LINE OPTIONS

    /** name of the model directory */
    @Argument(index = 0, metaVar = "modelDir", usage = "directory containing the RNA Seq classifier", required = true)
    private File modelDir;

    /** name of the groups file */
    @Argument(index = 1, metaVar = "groups.tbl", usage = " modulon/regulon definition file", required = true)
    private File groupFile;

    /** name of the reference genome file */
    @Argument(index = 2, metaVar = "refGenome.gto", usage = "reference-genome file", required = true)
    private File gtoFile;

    /**
     * This object describes all the data we keep about a gene.
     */
    private class Gene {

        /** ID of the feature */
        private String figId;
        /** gene name */
        private String gene;
        /** functional assignment */
        private String function;
        /** comma-delimited list of iModulons (or empty string if none) */
        private String modulons;
        /** atomic regulon ID number, or 0 if none */
        private int regulon;
        /** entropy impact on classification */
        private double impact;
        /** pearson correlation to threonine output */
        private double corr;
        /** comma-delimited list of subsystem IDs (or empty string if none) */
        private String subs;

        /**
         * Create a gene descriptor from gene impact data.
         *
         * @param geneId	gene identifier (gene name, dot, peg number)
         * @param impact	entropy impact value
         */
        public Gene(String geneId, double impact) {
            String[] parts = StringUtils.split(geneId, ".");
            this.gene = parts[0];
            this.impact = impact;
            // The FIG ID is computed from the peg number and the genome ID.
            String genomeId = RnaSeqImpactProcessor.this.refGenome.getId();
            this.figId = "fig|" + genomeId + ".peg." + parts[1];
            // Clear the other indicators.
            this.regulon = 0;
            this.modulons = "";
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
         * @return the output line for this gene
         */
        public String toLine() {
            String arName = (this.regulon == 0 ? "" : String.format("AR%d", this.regulon));
            return String.format("%s\t%s\t%6.4f\t%6.4f\t%s\t%s\t%s\t%s", this.figId, this.gene,
                    this.impact, this.corr, this.function, arName, this.modulons,
                    this.subs);
        }

        /**
         * Specify the modulons for this gene.
         *
         * @param modulons 		the modulon string
         */
        public void setModulons(String modulons) {
            this.modulons = modulons;
        }

        /**
         * Specify the atomic regulon for this gene.
         *
         * @param regulon 		the atomic regulon number
         */
        public void setRegulon(int regulon) {
            this.regulon = regulon;
        }

        /**
         * Specify this gene's correlation to the threonine output.
         *
         * @param corr	the relevant correlation value
         */
        public void setCorr(double corr) {
            this.corr = corr;
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
        this.impactFile = new File(this.modelDir, "impact.tbl");
        if (! this.impactFile.canRead())
            throw new FileNotFoundException("Impact file " + this.impactFile + " is not found or unreadable.");
        // Verify that the modulon/regulon file exists.
        if (! this.groupFile.canRead())
            throw new FileNotFoundException("Modulon/regulon file " + this.groupFile + " is not found or unreadable.");
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
        this.geneList = new ArrayList<Gene>(1000);
        // Initialize the gene map from the impact file.  Note we only keep genes with nonzero impact.
        try (TabbedLineReader impactStream = new TabbedLineReader(this.impactFile)) {
            log.info("Reading impact data from {}.", this.impactFile);
            for (TabbedLineReader.Line line : impactStream) {
                String colName = line.get(0);
                double impact = line.getDouble(1);
                if (impact > 0.0) {
                    Gene geneDescriptor = new Gene(colName, impact);
                    this.geneList.add(geneDescriptor);
                    this.geneMap.put(getPegNumber(colName), geneDescriptor);
                }
            }
            log.info("{} impactful genes found.", this.geneList.size());
        }
        // Add the pearson correlations.
        try (TabbedLineReader corrStream = new TabbedLineReader(this.corrFile)) {
            log.info("Reading correlation data from {}.", this.corrFile);
            int count = 0;
            for (TabbedLineReader.Line line : corrStream) {
                String colName = line.get(0);
                // If this gene is interesting to us, it will be in the map.
                Gene geneDescriptor = this.geneMap.get(getPegNumber(colName));
                if (geneDescriptor != null) {
                    geneDescriptor.setCorr(line.getDouble(1));
                    count++;
                }
            }
            log.info("{} correlations stored.", count);
        }
        // Finally, add the modulon/regulon data.
        try (TabbedLineReader regStream = new TabbedLineReader(this.groupFile)) {
            log.info("Reading modulon/regulon data from {}.", this.groupFile);
            for (TabbedLineReader.Line line : regStream) {
                String pegId = line.get(0);
                // Again, if the peg is interesting, it will be in the map.
                Gene geneDescriptor = this.geneMap.get(getPegNumber(pegId));
                if (geneDescriptor != null) {
                    String modulon = line.get(1);
                    if (! modulon.isEmpty())
                        geneDescriptor.setModulons(modulon);
                    geneDescriptor.setRegulon(line.getInt(2));
                }
            }
        }
        // Now write the output in its original order.
        try (PrintWriter writer = new PrintWriter(System.out)) {
            log.info("Writing output.");
            writer.println(HEADER_LINE);
            for (Gene geneDescriptor : this.geneList)
                writer.println(geneDescriptor.toLine());
        }
    }

    /**
     * @return the peg number for a FIG ID or column name
     */
    public static String getPegNumber(String pegId) {
        return StringUtils.substringAfterLast(pegId, ".");
    }

}
