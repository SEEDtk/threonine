package org.theseed.threonine;

import java.util.Arrays;

import org.theseed.utils.BaseProcessor;

/**
 * Commands for Threonine Project Utilities
 *
 * thrfix		reconcile threonine growth files
 * prod			format production data
 * thrall		generate a prediction input file for all possible samples
 * impact		determine the impact of each sample fragment in a set of predictions
 * thrBuild		process experiment results to produce the input for "thrfix"
 * rnaSeqRep	compare RNA seq expression data for two samples
 * rnaSeqClass	produce a random forest training set from the rna seq database
 * rnaSeqCorr	compute the correlation between threonine production and RNA expression data for each feature
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
        case "impact" :
            processor = new ImpactProcessor();
            break;
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
