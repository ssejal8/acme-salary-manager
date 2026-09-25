/**
 * Saving a file the browser fetched itself.
 *
 * A plain `<a href="/api/v1/payslips/9/pdf" download>` would be simpler and does not work
 * here: every API request carries a bearer token added by an interceptor, and a link
 * navigation carries no headers — so the download would arrive as a 401. The file has to
 * be fetched as a blob and then handed to the browser, which is what this does.
 */
export function saveBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName;
  // Appended before clicking: Firefox ignores a click on an anchor that is not in the
  // document, where Chrome does not, and the difference is invisible until someone
  // reports that downloads do nothing in one browser.
  document.body.appendChild(link);
  link.click();
  link.remove();
  // The object URL holds the blob in memory until it is revoked, and a payslip PDF is
  // not large — but a screen somebody downloads twenty of would keep all twenty.
  URL.revokeObjectURL(url);
}
