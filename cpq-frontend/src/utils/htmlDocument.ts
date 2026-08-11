export function openHtmlDocument(html: string, printOnLoad = false): Window | null {
  const blob = new Blob([html], { type: 'text/html;charset=utf-8' });
  const objectUrl = URL.createObjectURL(blob);
  const opened = window.open(objectUrl, '_blank');
  if (!opened) {
    URL.revokeObjectURL(objectUrl);
    return null;
  }
  opened.opener = null;
  opened.onload = () => {
    if (printOnLoad) opened.print();
    URL.revokeObjectURL(objectUrl);
  };
  return opened;
}
