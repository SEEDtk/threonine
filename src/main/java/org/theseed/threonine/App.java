package org.theseed.threonine;

import java.util.Arrays;

import org.theseed.utils.BaseProcessor;

/**
 * Commands for Threonine Project Utilities
 *
 * thrfix		reconcile threonine growth files
 * prod			format production data
 * thrall		generate a prediction input file for all possible samples
 * thrBuild		process experiment results to produce the input for "thrfix"
 * rnaSeqRep	compare RNA seq expression data for two samples
 * rnaSeqClass	produce a random forest training set from the rna seq database
 * rnaSeqCorr	compute the correlation between threonine production and RNA expression data for each feature
 * seqImpact	produce the RNA seq impact report
 * merge		merge the model prediction file with a virtual-space prediction file
 * expConst		look for genes with generally constant expression levels
 * diffTables	create a sample-difference table spreadsheet
 * fillPegs		add missing pegs to a tab-delimited file
 * extract		extract certain types of samples from a master table
 *
 */
public class App
{
    public static void main( String[] args )
    {
        // Get the control parameter.
        String command = args[0];
        String[] newArgs = Arrays.copyOfRange(args, 1, args.length);
        BaseProcessor processor;
        // Determine the command to process.
        switch (command) {
        case "thrfix" :
            processor = new ThrFixProcessor();
            break;
        case "prod" :
            processor = new ProdFormatProcessor();
            break;
        case "thrall" :
            processor = new ThrallProcessor();
            break;
        case "rnaSeqComp" :
            processor = new RnaSeqCompareProcessor();
            break;
        case "rnaSeqReps" :
            processor = new RnaSeqRepsProcessor();
            break;
        case "rnaSeqClass" :
            processor = new RnaSeqClassProcessor();
            break;
        case "rnaSeqCorr" :
            processor = new RnaSeqCorrespondenceProcessor();
            break;
        case "thrBuild" :
            processor = new ThrSetBuildProcessor();
            break;
        case "seqImpact" :
            processor = new RnaSeqImpactProcessor();
            break;
        case "merge" :
            processor = new MergePredictionsProcessor();
            break;
        case "expConst" :
            processor = new ConstantExpressionProcessor();
            break;
        case "diffTables" :
            processor = new DiffTableProcessor();
            break;
        case "fillPegs" :
            processor = new FillPegsProcessor();
            break;
        case "prodMatrix" :
            processor = new ProdMatrixProcessor();
            break;
        case "extract" :
            processor = new ExtractProcessor();
            break;
        default:
            throw new RuntimeException("Invalid command " + command);
        }
        // Process it.
        boolean ok = processor.parseCommand(newArgs);
        if (ok) {
            processor.run();
        }
    }
}
