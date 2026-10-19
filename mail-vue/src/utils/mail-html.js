// A small display allowlist; the sandbox and CSP provide independent isolation.
const tags = new Set('p br div span b strong i em u s blockquote pre code h1 h2 h3 h4 h5 h6 ul ol li table tbody thead tfoot tr td th hr a img'.split(' '));
const discard = new Set('script style link meta base iframe object embed form input button textarea select svg math video audio source'.split(' '));
const styles = new Set('color background-color font-size font-weight font-style text-align text-decoration border border-collapse padding margin width max-width height vertical-align white-space'.split(' '));
export function safeMailHtml(html, { remoteImages = false } = {}) {
  const parsed = document.createElement('template');
  parsed.innerHTML = html || ''; // Template content is inert, including image loads.
  function clean(node) {
    if (node.nodeType === 3) return document.createTextNode(node.textContent);
    if (node.nodeType !== 1 || discard.has(node.localName)) return document.createTextNode('');
    const result = document.createElement(tags.has(node.localName) ? node.localName : 'span');
    for (const rule of (node.getAttribute('style') || '').split(';')) {
      const split = rule.indexOf(':');
      const key = rule.slice(0, split).trim().toLowerCase();
      const value = rule.slice(split + 1).trim();
      if (split > 0 && styles.has(key) && /^[#a-zA-Z0-9\s.,%()-]+$/.test(value) && !/url|expression|var\s*\(/i.test(value)) result.style.setProperty(key, value);
    }
    if (node.localName === 'img') {
      const source = node.getAttribute('src') || '';
      let url; try { url = new URL(source); } catch { /* Relative images stay blocked. */ }
      if (source.startsWith('blob:') || (remoteImages && url?.protocol === 'https:' && !url.username && !url.password)) result.setAttribute('src', source);
      result.setAttribute('alt', node.getAttribute('alt') || 'Image'); result.style.maxWidth = '100%';
    }
    if (node.localName === 'a') {
      let url; try { url = new URL(node.getAttribute('href')); } catch { /* No implicit schemes. */ }
      if (url && ['https:', 'http:'].includes(url.protocol) && !url.username && !url.password) {
        result.setAttribute('href', url.href); result.setAttribute('rel', 'noopener noreferrer');
      }
    }
    for (const child of node.childNodes) result.appendChild(clean(child));
    return result;
  }
  const output = document.createElement('div');
  for (const child of parsed.content.childNodes) output.appendChild(clean(child));
  return output.innerHTML;
}
export function mailDocument(html, remoteImages = false) {
  const policy = `default-src 'none'; script-src 'none'; style-src 'unsafe-inline'; img-src blob:${remoteImages ? ' https:' : ''}; frame-src 'none'; connect-src 'none'; form-action 'none'; base-uri 'none'`;
  return `<!doctype html><html><head><meta http-equiv="Content-Security-Policy" content="${policy}"><meta name="referrer" content="no-referrer"><style>body{margin:0;padding:4px;font:14px/1.6 system-ui;color:#18202b;background:#fff;overflow-wrap:anywhere}img{max-width:100%;height:auto}table{max-width:100%}pre{white-space:pre-wrap}a{color:#2463b3}blockquote{border-left:2px solid #ccd5df;padding-left:12px;margin-left:0}</style></head><body>${safeMailHtml(html, {remoteImages})}</body></html>`;
}

export function escapeMailText(text) {
  const node = document.createElement('span'); node.textContent = text || ''; return node.innerHTML;
}
