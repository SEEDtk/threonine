package org.theseed.threonine;

import java.util.Arrays;

import org.theseed.utils.BaseProcessor;

/**
 * Commands for Threonine Project Utilities
 *
 * thrfix		reconcile threonine growth files
 * prod			format production data
 * thrall		generate a prediction input file for all possible samples
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
