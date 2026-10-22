package com.uav.lowaltitude.modules.reporting.infrastructure;

import org.apache.poi.ss.usermodel.FontScheme;
import org.apache.poi.ss.usermodel.HeaderFooter;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Applies the report's open font to every font record, including the workbook default. */
final class ReportWorkbookFonts {
    private static final String FAMILY = "Noto Sans SC";
    private static final String HEADER_FONT = "&\"" + FAMILY + ",Regular\"";

    private ReportWorkbookFonts() { }

    static void apply(XSSFWorkbook workbook) {
        for (int index = 0; index < workbook.getNumberOfFonts(); index++) {
            var font = workbook.getFontAt(index);
            font.setFontName(FAMILY);
            // A theme scheme can take precedence over the explicit font family in Excel.
            font.setScheme(FontScheme.NONE);
        }
        for (Sheet sheet : workbook) {
            apply(sheet.getHeader());
            apply(sheet.getFooter());
        }
    }

    private static void apply(HeaderFooter part) {
        if (!part.getLeft().isEmpty()) part.setLeft(HEADER_FONT + part.getLeft());
        if (!part.getCenter().isEmpty()) part.setCenter(HEADER_FONT + part.getCenter());
        if (!part.getRight().isEmpty()) part.setRight(HEADER_FONT + part.getRight());
    }
}
