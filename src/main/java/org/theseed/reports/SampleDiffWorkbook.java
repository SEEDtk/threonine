/**
 *
 */
package org.theseed.reports;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.samples.SampleDiffTable;

/**
 * This object represents a workbook to contain sample difference reports.  Each report will
 * be in a separate sheet, but they will use a common set of formats.
 *
 * @author Bruce Parrello
 *
 */
public class SampleDiffWorkbook {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(SampleDiffWorkbook.class);
    /** current workbook */
    private Workbook workbook;
    /** header style */
    private CellStyle headStyle;
    /** normal style */
    private CellStyle numStyle;
    /** number header style */
    private CellStyle numHeadStyle;
    /** style for mean values */
    private CellStyle meanStyle;
    /** output file name */
    private File outFile;
    /** minimum number of entries required per row */
    private int minWidth;

    /**
     * Create the workbook.
     *
     * @param file		output file name
     * @param minWidth 	minimum number of entries required per output row
     */
    public SampleDiffWorkbook(File file, int minWidth) {
        this.outFile = file;
        this.minWidth = minWidth;
        this.workbook = new XSSFWorkbook();
        // Get a data formatter.
        DataFormat format = workbook.createDataFormat();
        short fmt = format.getFormat("#0.0000");
        // Create the header style.
        this.headStyle = workbook.createCellStyle();
        this.headStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        this.headStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        // Create the number style.
        this.numStyle = workbook.createCellStyle();
        this.numStyle.setDataFormat(fmt);
        this.numStyle.setAlignment(HorizontalAlignment.RIGHT);
        // Create the mean-value style.
        this.meanStyle = workbook.createCellStyle();
        this.meanStyle.setDataFormat(fmt);
        this.meanStyle.setAlignment(HorizontalAlignment.RIGHT);
        this.meanStyle.setFillForegroundColor(IndexedColors.YELLOW.getIndex());
        this.meanStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        // Create the number header style.
        this.numHeadStyle = workbook.createCellStyle();
        this.numHeadStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        this.numHeadStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        this.numHeadStyle.setAlignment(HorizontalAlignment.RIGHT);

    }

    /**
     * Store a number in the specified cell of a row.
     *
     * @param row	spreadsheet row to update
     * @param idx	column index
     * @param val	number to store
     */
    private void store(Row row, int idx, double val) {
        Cell cell = row.createCell(idx);
        cell.setCellValue(val);
        cell.setCellStyle(this.numStyle);
    }

    /**
     * Store a string in the specified cell of a row.
     *
     * @param row		spreadsheet row to update
     * @param idx		column index
     * @param val		string to store
     * @param style		style for the cell
     */
    private void store(Row row, int idx, String val, CellStyle style) {
        Cell cell = row.createCell(idx);
        cell.setCellValue(val);
        cell.setCellStyle(style);
    }

    /**
     * Create a worksheet containing the results from the specified sample difference table.
     *
     * @param name		name to give the sheet
     * @param table		data table for the sheet
     */
    public void createSheet(String name, SampleDiffTable table) {
        // Create the new worksheet.
        Sheet sheet = this.workbook.createSheet(name);
        // We will use this to build a map of choices to column numbers.
        Map<String, Integer> choiceMap = new HashMap<String, Integer>();
        // Build the header row.
        Row row = sheet.createRow(0);
        this.store(row, 0, "Sample ID", this.headStyle);
        int cIdx = 1;
        for (String choice : table.getChoices()) {
            this.store(row, cIdx, choice, this.numHeadStyle);
            choiceMap.put(choice, cIdx);
            cIdx++;
        }
        // Save the column index for later. (Yes, this is redundant, but someday someone might accidentally
        // modify cIdx later on.)
        int nCols = cIdx;
        // Build a row for each sample.  We reserve the first row for each choice's mean.
        Row meanRow = sheet.createRow(1);
        int rowNum = 2;
        for (String sampleId : table.getSamples()) {
            List<SampleDiffTable.Entry> entryList = table.getSampleEntries(sampleId);
            // We only output if the row has enough data in it for comparison.
            if (entryList.size() >= this.minWidth) {
                row = sheet.createRow(rowNum);
                rowNum++;
                // Put the generic sample ID in the first cell.
                this.store(row, 0, sampleId, this.headStyle);
                // Loop through the results, placing them in the proper columns.
                for (SampleDiffTable.Entry resultEntry : entryList) {
                    String choice = resultEntry.getChoice();
                    this.store(row, choiceMap.get(choice), resultEntry.getProduction());
                }
            }
        }
        // Now fill in the row for the means.
        this.store(meanRow, 0, "Mean Production", this.headStyle);
        for (int i = 1; i < nCols; i++) {
            // Create the formula for the mean.
            String col = CellReference.convertNumToColString(i);
            String formula = String.format("AVERAGE(%s3:%s%d)", col, col, rowNum);
            Cell cell = meanRow.createCell(i);
            cell.setCellFormula(formula);
            cell.setCellStyle(this.meanStyle);
        }
        // Fix the width of the first column.
        sheet.autoSizeColumn(0);
        sheet.createFreezePane(1, 2);
    }

    /**
     * Write out the workbook.
     *
     * @throws IOException
     */
    public void save() throws IOException {
        try (FileOutputStream outStream = new FileOutputStream(this.outFile)) {
            this.workbook.write(outStream);
        }
    }

}
