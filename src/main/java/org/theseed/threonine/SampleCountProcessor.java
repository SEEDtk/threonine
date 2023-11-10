/**
 * 
 */
package org.theseed.threonine;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.basic.BaseProcessor;
import org.theseed.basic.ParseFailureException;
import org.theseed.io.TabbedLineReader;
import org.theseed.samples.SampleId;

/**
 * This is a very simple report that counts the samples and strains in the big production table.
 * We will return the number of strains, the total number of samples, and the total number of
 * observations.
 * 
 * The big production table will be read from the standard input.  The report will be written to
 * the standard output.
 * 
 * There are no positional parameters.  The command-line options are as follows.
 * 
 * -h	display command-line usage
 * -v	display more frequent log messages
 * -i	name of the big production table (if not STDIN)
 * 
 * --constructed	if specified, only artificial strains will be included
 * 
 * @author Bruce Parrello
 *
 */
public class SampleCountProcessor extends BaseProcessor {
	
	// FIELDS
	/** logging facility */
	protected static Logger log = LoggerFactory.getLogger(SampleCountProcessor.class);
	/** set of strains found */
	private Set<SampleId> strains;
	/** number of samples found */
	private int sampleCount;
	/** number of observations found */
	private int obsCount;
	/** input stream */
	private TabbedLineReader inStream;
	
	// COMMAND-LINE OPTIONS
	
	/** input file (if not STDIN) */
	@Option(name = "--input", aliases = { "-i" }, metaVar = "big_production_table.tbl",
			usage = "input big production table (if not via STDIN)")
	private File inFile;

	/** if specified, only constructed strains will be listed */
	@Option(name = "--constructed", usage = "if specified, only constructed strains will be processed")
	private boolean constructed;
	
	@Override
	protected void setDefaults() {
		this.inFile = null;
		this.constructed = false;
	}

	@Override
	protected boolean validateParms() throws IOException, ParseFailureException {
		// Validate the input file.
		if (this.inFile == null) {
			this.inStream = new TabbedLineReader(System.in);
			log.info("Production table will be read from the standard input.");
		} else if (! this.inFile.canRead())
			throw new FileNotFoundException("Input file " + this.inFile + " is not found or unreadable.");
		else {
			this.inStream = new TabbedLineReader(this.inFile);
			log.info("Production table will be read from {}.", this.inFile);
		}
		if (this.constructed)
			log.info("Filtering for constructed strains.");
		return true;
	}

	@Override
	protected void runCommand() throws Exception {
		// Initialize the counting tools.
		this.sampleCount = 0;
		this.obsCount = 0;
		this.strains = new HashSet<SampleId>(1000);
		int rejected = 0;
		// Find the key columns.  We need the origin column and the sample column.
		int originCol = this.inStream.findField("origins");
		int sampleCol = this.inStream.findField("sample");
		// Loop through the production table.
		for (TabbedLineReader.Line line : this.inStream) {
			// Get the strain for this row.
			SampleId sample = new SampleId(line.get(sampleCol)).asStrain();
			// Check the filtering.  We may be removing constructed strains.
			boolean keep = (! this.constructed || sample.isConstructed());
			if (! keep)
				rejected++;
			else {
				// Count the row.
				this.sampleCount++;
				// Remember the strain.
				this.strains.add(sample);
				// Parse the origins to get the number of observations.
				String origins = line.get(originCol);
				int observations = 1 + StringUtils.countMatches(origins, ',');
				this.obsCount += observations;
			}
		}
		log.info("{} samples rejected by filter.", rejected);
		// Write the counts.
		System.out.format("Strains\t%d%n", this.strains.size());
		System.out.format("Samples\t%d%n", this.sampleCount);
		System.out.format("Observations\t%d%n", this.obsCount);
	}

}
