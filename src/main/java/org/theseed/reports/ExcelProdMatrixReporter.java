/**
 *
 */
package org.theseed.reports;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;

import org.apache.poi.hssf.util.HSSFColor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This report version writes the data to an Excel file and generates a surface graph for each individual matrix.
 * @author Bruce Parrello
 *
 */
public class ExcelProdMatrixReporter extends ProdMatrixReporter {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(ExcelProdMatrixReporter.class);
    /** current workbook */
    private Workbook workbook;
    /** current worksheet */
    private Sheet worksheet;
    /** next row number */
    private int rowNum;
    /** current row */
    private Row ssRow;
    /** header style */
    private CellStyle headStyle;
    /** normal style */
    private CellStyle numStyle;
    /** blank style */
    private CellStyle blankStyle;
    /** number of columns in each row */
    private int colCount;


    public ExcelProdMatrixReporter(File outFile) throws FileNotFoundException {
        super(outFile);
        this.colCount = 0;
    }

    @Override
    protected void initReport(OutputStream oStream) {
        log.info("Initializing workbook.");
        // Create the workbook and the sheet.
        this.workbook = new XSSFWorkbook();
        this.worksheet = this.workbook.createSheet("Matrices");
        // Get a data formatter.
        DataFormat format = this.workbook.createDataFormat();
        short fmt = format.getFormat("###0.0000");
        // Create the header style.
        this.headStyle = this.workbook.createCellStyle();
        this.headStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        this.headStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        // Create the number style.
        this.numStyle = this.workbook.createCellStyle();
        this.numStyle.setDataFormat(fmt);
        // Create the style for empty cells (which are filled with 0s for graphing.)
        this.blankStyle = this.workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setColor(HSSFColor.HSSFColorPredefined.WHITE.getIndex());
        this.blankStyle.setFont(font);
        // Start on the first row.
        this.rowNum = 0;
    }

    @Override
    protected void skipRow() {
        this.rowNum++;
    }

    @Override
    protected void writeRow(String label, double[] ds) {
        this.addRow();
        this.setHeadCell(0, label);
        for (int i = 0; i < ds.length; i++) {
            Cell dataCell = this.ssRow.createCell(i + 1);
            // For a missing value, we set it to 0 and make the cell white.
            CellStyle cellStyle = this.numStyle;
            double value = ds[i];
            if (Double.isNaN(value)) {
                value = 0.0;
                cellStyle = this.blankStyle;
            }
            dataCell.setCellValue(value);
            dataCell.setCellStyle(cellStyle);
        }
    }

    /**
     * Store a label in a header cell.
     *
     * @param col		column index of cell
     * @param label		text to store in cell
     */
    private void setHeadCell(int col, String label) {
        Cell labelCell = this.ssRow.createCell(col);
        labelCell.setCellValue(label);
        labelCell.setCellStyle(this.headStyle);
    }

    /**
     * Add a new row to the spreadsheet.
     */
    private void addRow() {
        this.ssRow = this.worksheet.createRow(this.rowNum);
        this.rowNum++;
    }

    @Override
    protected void writeHeaders(String label, String[] columns) {
        this.addRow();
        this.setHeadCell(0, label);
        for (int i = 0; i < columns.length; i++)
            this.setHeadCell(i + 1, columns[i]);
        // Update the column count.
        int allCols = columns.length + 1;
        if (allCols > this.colCount)
            this.colCount = allCols;
    }

    @Override
    protected void cleanup() throws IOException {
        for (int i = 0; i < this.colCount; i++)
            this.worksheet.autoSizeColumn(i);
        // Fix the column sizes.
        log.info("Writing workbook.");
        this.workbook.write(this.getOutStream());
    }

}
