// Credentials go only to the configured API, never to a URL from a message.
export async function privateAttachmentBlob(key, signal) {
  if (typeof key !== 'string' || !key.startsWith('attachments/') || key.slice(12).length === 0 || /[\/\\\u0000-\u001f\u007f]/.test(key.slice(12)) || ['.', '..'].includes(key.slice(12))) throw new Error('Invalid attachment key');
  const api = (import.meta.env.VITE_BASE_URL || '/api').replace(/\/$/, '');
  const response = await fetch(`${api}/oss/attachments/${encodeURIComponent(key.slice(12))}`, {
    headers: { Authorization: localStorage.getItem('token') || '' }, cache: 'no-store', signal,
  });
  if (!response.ok) throw new Error(`Attachment unavailable (${response.status})`);
  if (Number(response.headers.get('content-length') || 0) > 25 * 1024 * 1024) throw new Error('Attachment exceeds 25 MiB');
  const reader = response.body.getReader();
  const parts = []; let size = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > 25 * 1024 * 1024) throw new Error('Attachment exceeds 25 MiB');
      parts.push(value);
    }
  } catch (error) { await reader.cancel(); throw error; }
  return new Blob(parts, { type: response.headers.get('content-type') || 'application/octet-stream' });
}
export function safeFilename(name) {
  return (name || 'attachment').replace(/[\\/\u0000-\u001f\u007f]/g, '_').slice(0, 180);
}
