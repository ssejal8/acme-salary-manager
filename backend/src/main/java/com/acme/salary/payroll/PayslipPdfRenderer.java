package com.acme.salary.payroll;

import com.acme.salary.common.money.Money;
import com.acme.salary.payroll.dto.PayslipDetailResponse;
import com.acme.salary.payroll.dto.PayslipLineResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName;

/**
 * Renders a payslip as a one-page PDF (FR-6.4).
 *
 * <p>Pure: a response record in, bytes out. No Spring, no filesystem, no request context —
 * which is what lets the layout be asserted by extracting the text back out of the
 * document rather than by eyeballing a file.
 *
 * <h2>Why a drawing API and not HTML</h2>
 *
 * A payslip is a fixed one-page document with a table of figures, not a web page. Rendering
 * it from HTML would mean pulling a layout engine into the build to reproduce a layout
 * nobody needs to reflow, and would make the output depend on CSS support rather than on
 * the figures.
 *
 * <h2>Two deliberate compromises, both visible in the output</h2>
 *
 * <p><b>Amounts read {@code INR 90,000.00} rather than {@code ₹90,000.00}.</b> The
 * standard PDF fonts use WinAnsi, which has no rupee sign (U+20B9), so the alternative is
 * embedding a Unicode font — a few hundred kilobytes in every response, and a font file in
 * the repository, for one glyph. The ISO code is unambiguous on a document that may be
 * printed or emailed anywhere.
 *
 * <p><b>Characters WinAnsi cannot encode are replaced rather than refused.</b> A name in
 * Devanagari would otherwise make PDFBox throw, and a payslip that fails to download is
 * worse than one with a transliteration gap. The screen and the API always carry the real
 * name; this limitation stops at the PDF.
 */
public final class PayslipPdfRenderer {

    /** A4, the paper this will actually be printed on. */
    private static final PDRectangle PAGE_SIZE = PDRectangle.A4;

    private static final float MARGIN = 48f;
    private static final float LINE_HEIGHT = 15f;
    private static final float SECTION_GAP = 22f;

    /** Where the amount column is right-aligned to. */
    private static final float AMOUNT_RIGHT_EDGE = PAGE_SIZE.getWidth() - MARGIN;

    private PayslipPdfRenderer() {
    }

    /**
     * @return the complete PDF; small enough to hold in memory, because one payslip is
     *     one page and the alternative is streaming a temporary file for a few kilobytes
     */
    public static byte[] render(PayslipDetailResponse payslip) {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {

            PDPage page = new PDPage(PAGE_SIZE);
            document.addPage(page);

            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                Cursor cursor = new Cursor(PAGE_SIZE.getHeight() - MARGIN);

                drawHeader(content, cursor, payslip);
                drawEmployee(content, cursor, payslip);
                drawAttendance(content, cursor, payslip);
                drawLines(content, cursor, "Earnings", payslip.earnings(), payslip.grossPay());
                drawLines(content, cursor, "Deductions", payslip.deductions(),
                        payslip.totalDeductions());
                drawNetPay(content, cursor, payslip);
                drawFooter(content, payslip);
            }

            document.save(bytes);
            return bytes.toByteArray();
        } catch (IOException e) {
            // PDFBox writes to an in-memory stream here, so an IOException means something
            // genuinely unexpected rather than a full disk or a closed socket.
            throw new IllegalStateException(
                    "could not render payslip " + payslip.id() + " as a PDF", e);
        }
    }

    /** A suggested filename, e.g. {@code payslip-E-1001-2026-08.pdf}. */
    public static String fileNameFor(PayslipDetailResponse payslip) {
        return "payslip-" + payslip.employee().employeeCode() + "-" + payslip.period() + ".pdf";
    }

    private static void drawHeader(
            PDPageContentStream content, Cursor cursor, PayslipDetailResponse payslip)
            throws IOException {
        text(content, cursor, "ACME Corporation", FontName.HELVETICA_BOLD, 16f, MARGIN);
        text(content, cursor, "Payslip for " + readablePeriod(payslip), FontName.HELVETICA, 12f,
                MARGIN);

        if (!payslip.published()) {
            // Stamped, not omitted: HR can download a draft while reviewing a run, and a
            // document that does not say so could be handed to an employee as final.
            text(content, cursor, "DRAFT — not published; figures may still change",
                    FontName.HELVETICA_BOLD, 10f, MARGIN);
        }
        cursor.down(SECTION_GAP);
    }

    private static void drawEmployee(
            PDPageContentStream content, Cursor cursor, PayslipDetailResponse payslip)
            throws IOException {
        var employee = payslip.employee();
        text(content, cursor, "Employee", FontName.HELVETICA_BOLD, 11f, MARGIN);
        labelled(content, cursor, "Name", employee.fullName());
        labelled(content, cursor, "Employee code", employee.employeeCode());
        labelled(content, cursor, "Department", employee.department());
        labelled(content, cursor, "Designation", employee.designation());
        cursor.down(SECTION_GAP);
    }

    private static void drawAttendance(
            PDPageContentStream content, Cursor cursor, PayslipDetailResponse payslip)
            throws IOException {
        text(content, cursor, "Attendance", FontName.HELVETICA_BOLD, 11f, MARGIN);
        labelled(content, cursor, "Days in period", String.valueOf(payslip.totalDays()));
        labelled(content, cursor, "Paid days", String.valueOf(payslip.paidDays()));
        labelled(content, cursor, "Loss of pay days", String.valueOf(payslip.lopDays()));
        if (payslip.lopDays() > 0) {
            // The commonest surprise on a payslip, so it is explained on the document
            // itself rather than only in the app.
            text(content, cursor,
                    "Earnings are prorated over paid days; fixed statutory deductions are not.",
                    FontName.HELVETICA_OBLIQUE, 9f, MARGIN);
        }
        cursor.down(SECTION_GAP);
    }

    /**
     * One table of lines with its own subtotal.
     *
     * <p>The subtotal is the figure the API computed, not a sum taken here. Amounts are
     * rounded per component before being summed (NFR-3.3), so adding them up again in a
     * different order could disagree with the payslip by a paisa — and a payslip whose
     * lines do not add up to its total is the one defect this system spends real effort
     * avoiding.
     */
    private static void drawLines(
            PDPageContentStream content,
            Cursor cursor,
            String heading,
            List<PayslipLineResponse> lines,
            BigDecimal subtotal) throws IOException {
        text(content, cursor, heading, FontName.HELVETICA_BOLD, 11f, MARGIN);

        for (PayslipLineResponse line : lines) {
            row(content, cursor, line.name() + " (" + line.code() + ")", amount(line.amount()),
                    FontName.HELVETICA, 10f);
        }
        row(content, cursor, "Total " + heading.toLowerCase(Locale.ROOT), amount(subtotal),
                FontName.HELVETICA_BOLD, 10f);
        cursor.down(SECTION_GAP);
    }

    private static void drawNetPay(
            PDPageContentStream content, Cursor cursor, PayslipDetailResponse payslip)
            throws IOException {
        row(content, cursor, "Net pay", amount(payslip.netPay()), FontName.HELVETICA_BOLD, 13f);
        // FR-6.3: rendered by the server, so it cannot disagree with the figure above it.
        text(content, cursor, payslip.netPayInWords(), FontName.HELVETICA_OBLIQUE, 10f, MARGIN);
    }

    private static void drawFooter(PDPageContentStream content, PayslipDetailResponse payslip)
            throws IOException {
        Cursor footer = new Cursor(MARGIN + LINE_HEIGHT);
        String note = payslip.published()
                ? "This is a computer-generated payslip and does not require a signature."
                : "Draft payslip, generated for review. Not valid as proof of payment.";
        text(content, footer, note, FontName.HELVETICA_OBLIQUE, 8f, MARGIN);
    }

    private static void labelled(
            PDPageContentStream content, Cursor cursor, String label, String value)
            throws IOException {
        text(content, cursor, label + ": " + value, FontName.HELVETICA, 10f, MARGIN);
    }

    /** A label on the left and a right-aligned amount, the way a payslip column reads. */
    private static void row(
            PDPageContentStream content,
            Cursor cursor,
            String label,
            String value,
            FontName font,
            float size) throws IOException {
        var typeface = new PDType1Font(font);
        float valueWidth = typeface.getStringWidth(encodable(value)) / 1000 * size;

        content.beginText();
        content.setFont(typeface, size);
        content.newLineAtOffset(MARGIN, cursor.y);
        content.showText(encodable(label));
        content.endText();

        content.beginText();
        content.setFont(typeface, size);
        content.newLineAtOffset(AMOUNT_RIGHT_EDGE - valueWidth, cursor.y);
        content.showText(encodable(value));
        content.endText();

        cursor.down(LINE_HEIGHT);
    }

    private static void text(
            PDPageContentStream content,
            Cursor cursor,
            String value,
            FontName font,
            float size,
            float x) throws IOException {
        content.beginText();
        content.setFont(new PDType1Font(font), size);
        content.newLineAtOffset(x, cursor.y);
        content.showText(encodable(value));
        content.endText();
        cursor.down(LINE_HEIGHT);
    }

    /** {@code INR 1,40,800.00} — see the class comment for why the symbol is not used. */
    private static String amount(BigDecimal value) {
        return "INR " + grouped(value);
    }

    /**
     * Groups digits the Indian way — 1,40,800.00, not 140,800.00 (NFR-4.4).
     *
     * <p>Implemented here rather than borrowed from the frontend's {@code money.ts},
     * because a PDF is rendered by the server and the browser cannot help. That is one
     * rule with two implementations, which is a cost worth naming: it is tested on both
     * sides, and the shape it produces — last three digits, then pairs — is short enough
     * to state completely.
     *
     * <p>Not {@code NumberFormat}: the value arrives as a {@code BigDecimal} at two
     * decimal places and is formatted from its own digits, so no amount ever passes
     * through a {@code double} on its way to a document somebody is paid against
     * (ADR-006).
     */
    private static String grouped(BigDecimal value) {
        String plain = Money.normalize(value).abs().toPlainString();
        int decimalPoint = plain.indexOf('.');
        String whole = plain.substring(0, decimalPoint);
        String fraction = plain.substring(decimalPoint);

        StringBuilder result = new StringBuilder();
        if (whole.length() > 3) {
            String lastThree = whole.substring(whole.length() - 3);
            String rest = whole.substring(0, whole.length() - 3);
            // Pairs, from the right, which is what makes it lakhs and crores.
            for (int index = rest.length(); index > 0; index -= 2) {
                int from = Math.max(0, index - 2);
                result.insert(0, rest.substring(from, index));
                if (from > 0) {
                    result.insert(0, ',');
                }
            }
            result.append(',').append(lastThree);
        } else {
            result.append(whole);
        }
        return (value.signum() < 0 ? "-" : "") + result + fraction;
    }

    /** {@code August 2026}, spelled out because a payslip is read by a person. */
    private static String readablePeriod(PayslipDetailResponse payslip) {
        YearMonth period = YearMonth.of(payslip.periodYear(), payslip.periodMonth());
        return period.getMonth().getDisplayName(TextStyle.FULL, Locale.UK) + " " + period.getYear();
    }

    /**
     * Replaces anything the standard PDF fonts cannot encode.
     *
     * <p>WinAnsi covers Latin-1 and little else, and {@code showText} throws on a character
     * outside it. A download that fails for a name in another script would be a worse
     * outcome than a replaced character, and the API and the screens always carry the real
     * name — so the limitation stops here rather than propagating.
     */
    private static String encodable(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder safe = new StringBuilder(value.length());
        for (char character : value.toCharArray()) {
            safe.append(isWinAnsi(character) ? character : '?');
        }
        return safe.toString();
    }

    private static boolean isWinAnsi(char character) {
        // Printable ASCII plus the Latin-1 supplement, which is the part of WinAnsi that
        // maps one-to-one with Unicode.
        return (character >= 0x20 && character <= 0x7E) || (character >= 0xA0 && character <= 0xFF);
    }

    /** Where the next line goes. A mutable y, rather than arithmetic at every call site. */
    private static final class Cursor {

        private float y;

        private Cursor(float y) {
            this.y = y;
        }

        private void down(float amount) {
            y -= amount;
        }
    }
}
