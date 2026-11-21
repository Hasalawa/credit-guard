const BASE = import.meta.env.VITE_API_URL || "http://localhost:8080";

/**
 * api("/api/invoices", { token })                       -> vendor call
 * api("/api/admin/vendors", { adminKey })               -> admin call
 * api("/api/invoices", { method:"POST", form, token })  -> file upload (FormData)
 * api("/api/auth/login", { method:"POST", body:{...} }) -> JSON body
 */
export async function api(path, { method = "GET", body, form, token, adminKey } = {}) {
  const headers = {};
  if (token) headers["Authorization"] = `Bearer ${token}`;
  if (adminKey) headers["X-Admin-Key"] = adminKey;
  let payload;
  if (form) {
    payload = form; // browser sets multipart boundary
  } else if (body) {
    headers["Content-Type"] = "application/json";
    payload = JSON.stringify(body);
  }

  let res;
  try {
    res = await fetch(BASE + path, { method, headers, body: payload });
  } catch {
    throw new Error("Cannot reach the backend. Is it running on " + BASE + " ?");
  }

  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { /* not json */ }
  if (!res.ok) {
    throw new Error((data && (data.message || data.error)) || `Request failed (${res.status})`);
  }
  return data;
}

export const money = (n) =>
  "Rs. " + Number(n || 0).toLocaleString("en-LK", { maximumFractionDigits: 2 });
