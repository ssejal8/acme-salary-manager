package com.acme.salary.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.acme.salary.common.money.Money;
import com.acme.salary.payroll.dto.PayslipDetailResponse;
import com.acme.salary.payroll.dto.PayslipLineResponse;
import com.acme.salary.salarycomponent.ComponentType;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The payslip PDF (FR-6.4).
 *
 * <p>Asserted by reading the text back out of the rendered document. That is the only way
 * a generated PDF can be tested without a human looking at it: a test that checked the
 * byte length, or that the file starts with {@code %PDF}, would pass for a blank page.
 */
class PayslipPdfRendererTest {

    private static PayslipDetailResponse payslip(boolean published, int lopDays) {
        return new PayslipDetailResponse(
                90L,
                2026,
                8,
                "2026-08",
                published ? PayrollRunStatus.FINALISED : PayrollRunStatus.DRAFT,
                published,
                published ? Instant.parse("2026-09-01T10:00:00Z") : null,
                new PayslipDetailResponse.Employee(
                        1001L, "E-1001", "Asha Menon", "asha.menon@acme.test",
                        "Engineering", "Senior Software Engineer"),
                31,
                31 - lopDays,
                lopDays,
                Money.of("140800.00"),
                Money.of("9200.00"),
                Money.of("131600.00"),
                "One Lakh Thirty One Thousand Six Hundred Rupees Only",
                List.of(
                        new PayslipLineResponse("BASIC", "Basic Salary", ComponentType.EARNING,
                                Money.of("75000.00")),
                        new PayslipLineResponse("HRA", "House Rent Allowance",
                                ComponentType.EARNING, Money.of("65800.00"))),
                List.of(
                        new PayslipLineResponse("PF", "Provident Fund", ComponentType.DEDUCTION,
                                Money.of("9000.00")),
                        new PayslipLineResponse("PROF_TAX", "Professional Tax",
                                ComponentType.DEDUCTION, Money.of("200.00"))));
    }

    /** The rendered document's text, as a reader would see it. */
    private static String textOf(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(new ByteArrayInputStream(pdf).readAllBytes())) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            return new PDFTextStripper().getText(document);
        }
    }

    @Test
    void producesAValidSinglePagePdf() throws IOException {
        byte[] pdf = PayslipPdfRenderer.render(payslip(true, 0));

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1))
                .isEqualTo("%PDF-");
        // Loading it is the real assertion: a malformed document fails here.
        assertThat(textOf(pdf)).isNotBlank();
    }

    @Nested
    @DisplayName("what the document says")
    class Content {

        @Test
        void namesTheEmployeeAndThePeriodSoItStandsAlone() throws IOException {
            // FR-6.2: a payslip has to be readable on its own, away from the application.
            String text = textOf(PayslipPdfRenderer.render(payslip(true, 0)));

            assertThat(text)
                    .contains("ACME Corporation")
                    .contains("Payslip for August 2026")
                    .contains("Asha Menon")
                    .contains("E-1001")
                    .contains("Engineering")
                    .contains("Senior Software Engineer");
        }

        @Test
        void showsEveryEarningAndDeductionLine() throws IOException {
            String text = textOf(PayslipPdfRenderer.render(payslip(true, 0)));

            assertThat(text)
                    .contains("Basic Salary (BASIC)")
                    .contains("House Rent Allowance (HRA)")
                    .contains("Provident Fund (PF)")
                    .contains("Professional Tax (PROF_TAX)");
        }

        @Test
        void showsTheServersSubtotalsRatherThanSummingTheLinesAgain() throws IOException {
            // Amounts are rounded per component before being summed (NFR-3.3), so a total
            // recomputed here could disagree with the payslip by a paisa.
            String text = textOf(PayslipPdfRenderer.render(payslip(true, 0)));

            assertThat(text)
                    .contains("INR 1,40,800.00")
                    .contains("INR 9,200.00")
                    .contains("INR 1,31,600.00");
        }

        @Test
        void groupsDigitsTheIndianWay() throws IOException {
            // NFR-4.4. 1,40,800.00 — not 140,800.00, which is what a default formatter
            // would produce.
            String text = textOf(PayslipPdfRenderer.render(payslip(true, 0)));

            assertThat(text).contains("1,40,800.00").doesNotContain("140,800.00");
        }

        @Test
        void spellsOutNetPay() throws IOException {
            // FR-6.3, and rendered by the server so it cannot disagree with the figure.
            String text = textOf(PayslipPdfRenderer.render(payslip(true, 0)));

            assertThat(text).contains("One Lakh Thirty One Thousand Six Hundred Rupees Only");
        }

        @Test
        void reportsAttendance() throws IOException {
            String text = textOf(PayslipPdfRenderer.render(payslip(true, 3)));

            assertThat(text)
                    .contains("Days in period: 31")
                    .contains("Paid days: 28")
                    .contains("Loss of pay days: 3");
        }

        @Test
        void explainsProrationOnlyWhenThereIsUnpaidLeave() throws IOException {
            // The commonest surprise on a payslip, so the document says it rather than
            // leaving the reader to work out why the month is smaller.
            assertThat(textOf(PayslipPdfRenderer.render(payslip(true, 3))))
                    .contains("Earnings are prorated");
            assertThat(textOf(PayslipPdfRenderer.render(payslip(true, 0))))
                    .doesNotContain("Earnings are prorated");
        }

        @Test
        void usesTheCurrencyCodeRatherThanASymbolItCannotEncode() throws IOException {
            // The standard PDF fonts are WinAnsi, which has no rupee sign. "INR" is
            // unambiguous; a missing glyph would not be.
            String text = textOf(PayslipPdfRenderer.render(payslip(true, 0)));

            assertThat(text).contains("INR").doesNotContain("₹");
        }
    }

    @Nested
    @DisplayName("a draft")
    class Draft {

        @Test
        void isStampedOnTheDocumentItself() throws IOException {
            // HR downloads drafts while reviewing a run. A document that did not say so
            // could be handed to an employee as final.
            String text = textOf(PayslipPdfRenderer.render(payslip(false, 0)));

            assertThat(text)
                    .contains("DRAFT")
                    .contains("figures may still change")
                    .contains("Not valid as proof of payment");
        }

        @Test
        void aPublishedPayslipCarriesNoDraftWording() throws IOException {
            String text = textOf(PayslipPdfRenderer.render(payslip(true, 0)));

            assertThat(text)
                    .doesNotContain("DRAFT")
                    .contains("computer-generated payslip");
        }
    }

    @Nested
    @DisplayName("names the standard fonts cannot encode")
    class Encoding {

        @Test
        void renderWithoutFailingTheDownload() throws IOException {
            // A name in Devanagari would make showText throw. A payslip that cannot be
            // downloaded is worse than one with a replaced character, and the API and the
            // screens always carry the real name.
            PayslipDetailResponse original = payslip(true, 0);
            PayslipDetailResponse devanagari = new PayslipDetailResponse(
                    original.id(), original.periodYear(), original.periodMonth(),
                    original.period(), original.runStatus(), original.published(),
                    original.publishedAt(),
                    new PayslipDetailResponse.Employee(1001L, "E-1001", "आशा मेनन",
                            "asha@acme.test", "Engineering", "Senior Software Engineer"),
                    original.totalDays(), original.paidDays(), original.lopDays(),
                    original.grossPay(), original.totalDeductions(), original.netPay(),
                    original.netPayInWords(), original.earnings(), original.deductions());

            String text = textOf(PayslipPdfRenderer.render(devanagari));

            // The document still renders, and everything that can be encoded still reads.
            assertThat(text).contains("E-1001").contains("INR 1,31,600.00");
        }

        @Test
        void keepAccentedLatinNamesIntact() throws IOException {
            // WinAnsi covers Latin-1, so a name like this must not be mangled.
            PayslipDetailResponse original = payslip(true, 0);
            PayslipDetailResponse accented = new PayslipDetailResponse(
                    original.id(), original.periodYear(), original.periodMonth(),
                    original.period(), original.runStatus(), original.published(),
                    original.publishedAt(),
                    new PayslipDetailResponse.Employee(1001L, "E-1002", "Zoë Fernándes",
                            "zoe@acme.test", "Finance", "Finance Analyst"),
                    original.totalDays(), original.paidDays(), original.lopDays(),
                    original.grossPay(), original.totalDeductions(), original.netPay(),
                    original.netPayInWords(), original.earnings(), original.deductions());

            assertThat(textOf(PayslipPdfRenderer.render(accented))).contains("Zoë Fernándes");
        }
    }

    @Test
    void suggestsAFilenameThatIdentifiesTheDocument() {
        assertThat(PayslipPdfRenderer.fileNameFor(payslip(true, 0)))
                .isEqualTo("payslip-E-1001-2026-08.pdf");
    }
}
