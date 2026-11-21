import { useEffect, useState } from "react";
import { api, money } from "./api.js";

const save = (k, v) => { try { v == null ? localStorage.removeItem(k) : localStorage.setItem(k, v); } catch {} };
const load = (k) => { try { return localStorage.getItem(k); } catch { return null; } };

function Msg({ m }) {
  if (!m) return null;
  return <div className={"msg " + (m.type === "err" ? "err" : "ok")}>{m.text}</div>;
}

/* ------------------------------------------------------------------ Login / Register */
function AuthPage({ onLogin }) {
  const [mode, setMode] = useState("login");
  const [f, setF] = useState({});
  const [msg, setMsg] = useState(null);
  const set = (k) => (e) => setF({ ...f, [k]: e.target.value });

  const submit = async (e) => {
    e.preventDefault();
    setMsg(null);
    try {
      if (mode === "login") {
        const r = await api("/api/auth/login", { method: "POST", body: { email: f.email, password: f.password } });
        onLogin(r.token);
      } else {
        const r = await api("/api/auth/register", { method: "POST", body: f });
        setMsg({ type: "ok", text: r.message });
        setMode("login");
      }
    } catch (err) { setMsg({ type: "err", text: err.message }); }
  };

  const fields = [
    ["businessName", "Business name"], ["brNumber", "Business Registration (BR) no."],
    ["nic", "NIC"], ["phone", "Phone"], ["bankAccount", "Business bank account no."],
  ];

  return (
    <div className="card" style={{ maxWidth: 440, margin: "30px auto" }}>
      <div className="tabs">
        <button className={mode === "login" ? "active" : ""} onClick={() => setMode("login")}>Login</button>
        <button className={mode === "register" ? "active" : ""} onClick={() => setMode("register")}>Register</button>
      </div>
      <Msg m={msg} />
      <form onSubmit={submit}>
        {mode === "register" && fields.map(([k, label]) => (
          <div key={k}><label>{label}</label><input required value={f[k] || ""} onChange={set(k)} /></div>
        ))}
        <label>Email</label><input type="email" required value={f.email || ""} onChange={set("email")} />
        <label>Password</label><input type="password" required minLength={6} value={f.password || ""} onChange={set("password")} />
        <p><button type="submit">{mode === "login" ? "Login" : "Register"}</button></p>
      </form>
      {mode === "register" && <div className="small">After registering, the association admin must approve you before you can log in.</div>}
    </div>
  );
}

/* ------------------------------------------------------------------ Vendor area */
function VendorHome({ token, onLogout }) {
  const [tab, setTab] = useState("invoices");
  const [me, setMe] = useState(null);
  const [list, setList] = useState([]);
  const [others, setOthers] = useState([]);
  const [msg, setMsg] = useState(null);
  const [busy, setBusy] = useState(false);
  const [settleRes, setSettleRes] = useState({});
  const [tgInfo, setTgInfo] = useState(null);

  const refresh = async () => {
    try {
      setMe(await api("/api/auth/me", { token }));
      setList(await api("/api/invoices", { token }));
      setOthers(await api("/api/vendors", { token }));
      setTgInfo(await api("/api/auth/telegram-link-code", { method: "POST", token }));
    } catch (e) {
      if (/log in|expired|revoked/i.test(e.message)) onLogout();
      setMsg({ type: "err", text: e.message });
    }
  };
  useEffect(() => { refresh(); }, []);
  useEffect(() => {
    if (me && !me.telegramChatId) {
      const timer = setInterval(async () => {
        try {
          const updated = await api("/api/auth/me", { token });
          if (updated && updated.telegramChatId) {
            setMe(updated);
            setMsg({ type: "ok", text: "✅ Telegram Bot successfully connected!" });
          }
        } catch {}
      }, 3000);
      return () => clearInterval(timer);
    }
  }, [me?.telegramChatId, token]);

  const act = async (fn) => {
    setBusy(true); setMsg(null);
    try { await fn(); await refresh(); } catch (e) { setMsg({ type: "err", text: e.message }); }
    setBusy(false);
  };

  const confirm = (id) => act(() => api(`/api/invoices/${id}/confirm`, { method: "POST", token }));
  const dispute = (id) => act(() => api(`/api/invoices/${id}/dispute`, { method: "POST", token }));
  const settle = (id, file) => act(async () => {
    const form = new FormData(); form.append("slip", file);
    const r = await api(`/api/invoices/${id}/settle`, { method: "POST", form, token });
    setSettleRes((s) => ({ ...s, [id]: r }));
    setMsg({ type: r.passed ? "ok" : "err", text: r.message });
  });

  return (
    <>
      <header>
        <h1>Credit-Guard</h1>
        <div className="who">
          {me && <span>{me.businessName} ({me.vendorCode}) · Trust score: <b>{me.trustScore}</b></span>}
          <button className="grey" onClick={onLogout}>Logout</button>
        </div>
      </header>
      <main>
        {me && (
          <div className="card" style={{ background: "#f0f9ff", borderColor: "#bae6fd", marginBottom: 14, padding: "12px 16px" }}>
            {me.telegramChatId ? (
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: 8 }}>
                <span>
                  <b>📲 Telegram Bot Connected</b> (Chat ID: <code>{me.telegramChatId}</code>) — You can upload bills (<code>/newbill</code>), confirm/dispute, upload slips (<code>/pay</code>) &amp; check risk (<code>/risk</code>) directly from Telegram.
                </span>
                <button className="grey" onClick={() => act(() => api("/api/auth/telegram-unlink", { method: "POST", token }))}>
                  Unlink Telegram
                </button>
              </div>
            ) : (
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: 8 }}>
                <div>
                  <b>📲 Connect Telegram Bot:</b> Open{" "}
                  {tgInfo?.botUsername ? (
                    <a href={`https://t.me/${tgInfo.botUsername}?start=${tgInfo.code}`} target="_blank" rel="noreferrer">
                      <b>@{tgInfo.botUsername}</b>
                    </a>
                  ) : "your Telegram Bot"}{" "}
                  and send: <code style={{ background: "#e0f2fe", padding: "2px 6px", borderRadius: 4 }}>/link {tgInfo?.code || me.telegramLinkCode || "..."}</code>
                  {tgInfo && !tgInfo.botConfigured && (
                    <span className="small" style={{ color: "#b91c1c", marginLeft: 8 }}>
                      (Set TELEGRAM_BOT_TOKEN in backend/.env to activate the bot)
                    </span>
                  )}
                </div>
                <button className="grey" onClick={refresh}>Check Connection</button>
              </div>
            )}
          </div>
        )}
        <div className="tabs">
          {[["invoices", "My invoices"], ["new", "Add credit invoice"], ["risk", "Check a buyer's risk"]].map(([k, l]) => (
            <button key={k} className={tab === k ? "active" : ""} onClick={() => setTab(k)}>{l}</button>
          ))}
        </div>
        <Msg m={msg} />
        {busy && <div className="msg ok">Working… (AI is reading the file, please wait)</div>}

        {tab === "invoices" && (
          <div className="card">
            <table>
              <thead><tr><th>Invoice</th><th>Role</th><th>Other party</th><th>Amount</th><th>Due</th><th>Status</th><th>Action</th></tr></thead>
              <tbody>
                {list.length === 0 && <tr><td colSpan="7">No invoices yet.</td></tr>}
                {list.map((i) => {
                  const iAmBuyer = me && i.buyerId === me.id;
                  const r = settleRes[i.id];
                  return (
                    <tr key={i.id}>
                      <td>{i.invoiceNo}<div className="small">{i.aiSummary}</div></td>
                      <td>{iAmBuyer ? "Buyer" : "Seller"}</td>
                      <td>{iAmBuyer ? i.sellerName : i.buyerName}</td>
                      <td>{money(i.amount)}</td>
                      <td>{i.dueDate}</td>
                      <td><span className={"badge " + i.status}>{i.status}</span></td>
                      <td>
                        {iAmBuyer && i.status === "PENDING_CONFIRMATION" && (
                          <div className="actions">
                            <button className="green" disabled={busy} onClick={() => confirm(i.id)}>Confirm</button>
                            <button className="red" disabled={busy} onClick={() => dispute(i.id)}>Dispute</button>
                          </div>
                        )}
                        {iAmBuyer && (i.status === "ACTIVE_DEBT" || i.status === "OVERDUE") && (
                          <label style={{ margin: 0 }}>
                            Upload bank slip to pay
                            <input type="file" accept="image/*,application/pdf" disabled={busy}
                              onChange={(e) => e.target.files[0] && settle(i.id, e.target.files[0])} />
                          </label>
                        )}
                        {r && <ul className="checks">{r.checks.map((c, n) => <li key={n}>{c}</li>)}</ul>}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}

        {tab === "new" && <NewInvoice token={token} others={others} onDone={() => { setTab("invoices"); refresh(); }} />}
        {tab === "risk" && <RiskCheck token={token} others={others} />}
      </main>
    </>
  );
}

function NewInvoice({ token, others, onDone }) {
  const [buyerId, setBuyerId] = useState("");
  const [days, setDays] = useState(30);
  const [file, setFile] = useState(null);
  const [msg, setMsg] = useState(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e) => {
    e.preventDefault(); setMsg(null); setBusy(true);
    try {
      const form = new FormData();
      form.append("buyerId", buyerId); form.append("creditDays", days); form.append("file", file);
      await api("/api/invoices", { method: "POST", form, token });
      onDone();
    } catch (err) { setMsg({ type: "err", text: err.message }); }
    setBusy(false);
  };

  return (
    <div className="card" style={{ maxWidth: 520 }}>
      <h3>Add a credit invoice</h3>
      <div className="small">Upload the bill (PDF or photo). Gemini reads it; the buyer must confirm before it counts as a debt.</div>
      <Msg m={msg} />
      <form onSubmit={submit}>
        <label>Buyer</label>
        <select required value={buyerId} onChange={(e) => setBuyerId(e.target.value)}>
          <option value="">-- select buyer --</option>
          {others.map((o) => <option key={o.id} value={o.id}>{o.businessName} ({o.vendorCode})</option>)}
        </select>
        <label>Credit days</label>
        <input type="number" min="1" max="365" value={days} onChange={(e) => setDays(e.target.value)} />
        <label>Invoice file (PDF / image)</label>
        <input type="file" required accept="image/*,application/pdf" onChange={(e) => setFile(e.target.files[0])} />
        <p><button type="submit" disabled={busy}>{busy ? "AI is reading…" : "Submit invoice"}</button></p>
      </form>
    </div>
  );
}

function RiskCheck({ token, others }) {
  const [id, setId] = useState("");
  const [amount, setAmount] = useState("");
  const [res, setRes] = useState(null);
  const [msg, setMsg] = useState(null);

  const go = async (e) => {
    e.preventDefault(); setMsg(null); setRes(null);
    try {
      setRes(await api(`/api/risk/${id}` + (amount ? `?amount=${amount}` : ""), { token }));
    } catch (err) { setMsg({ type: "err", text: err.message }); }
  };

  return (
    <div className="card" style={{ maxWidth: 560 }}>
      <h3>Should I give this buyer credit?</h3>
      <div className="small">Shows only a trust score and AI advice. Other sellers' names and bills are never shown.</div>
      <Msg m={msg} />
      <form onSubmit={go}>
        <label>Buyer</label>
        <select required value={id} onChange={(e) => setId(e.target.value)}>
          <option value="">-- select --</option>
          {others.map((o) => <option key={o.id} value={o.id}>{o.businessName} ({o.vendorCode})</option>)}
        </select>
        <label>Credit amount requested (Rs.) — optional</label>
        <input type="number" value={amount} onChange={(e) => setAmount(e.target.value)} />
        <p><button type="submit">Check risk</button></p>
      </form>
      {res && (
        <div className={"card risk " + res.riskLevel}>
          <b>{res.riskLevel} RISK</b> — {res.vendorName}<br />
          Credit Trust Score: <b>{res.trustScore} / 100</b> · Overdue debts: {res.overdueCount}
          <p style={{ whiteSpace: "pre-wrap" }}><b>AI recommendation:</b> {res.recommendation}</p>
        </div>
      )}
    </div>
  );
}

/* ------------------------------------------------------------------ Admin portal */
function Admin({ onExit }) {
  const [key, setKey] = useState(load("adminKey") || "");
  const [authed, setAuthed] = useState(false);
  const [vendors, setVendors] = useState([]);
  const [disputes, setDisputes] = useState([]);
  const [dash, setDash] = useState(null);
  const [msg, setMsg] = useState(null);

  const loadAll = async (k = key) => {
    try {
      setDash(await api("/api/admin/dashboard", { adminKey: k }));
      setVendors(await api("/api/admin/vendors", { adminKey: k }));
      setDisputes(await api("/api/admin/disputes", { adminKey: k }));
      setAuthed(true); save("adminKey", k); setMsg(null);
    } catch (e) { setAuthed(false); setMsg({ type: "err", text: e.message }); }
  };
  const post = async (path) => {
    try { const r = await api(path, { method: "POST", adminKey: key }); await loadAll(); return r; }
    catch (e) { setMsg({ type: "err", text: e.message }); }
  };

  return (
    <>
      <header><h1>Credit-Guard · Admin Portal</h1><button className="grey" onClick={onExit}>Back</button></header>
      <main>
        <Msg m={msg} />
        {!authed && (
          <div className="card" style={{ maxWidth: 400 }}>
            <label>Admin key (ADMIN_KEY in backend/.env)</label>
            <input type="password" value={key} onChange={(e) => setKey(e.target.value)} />
            <p><button onClick={() => loadAll()}>Open portal</button></p>
          </div>
        )}
        {authed && (
          <>
            {dash && (
              <div className="card">
                <h3>Community risk dashboard</h3>
                <div className="grid">
                  <div className="stat">Credit in circulation<b>{money(dash.totalCreditInCirculation)}</b></div>
                  <div className="stat">Overdue<b>{money(dash.overdueDebt)}</b></div>
                  <div className="stat">Bad-debt %<b>{dash.badDebtPercent}%</b></div>
                  <div className="stat">Settled total<b>{money(dash.settledTotal)}</b></div>
                  <div className="stat">Members (approved / pending)<b>{dash.vendorsApproved} / {dash.vendorsPending}</b></div>
                  <div className="stat">Open disputes<b>{dash.disputes}</b></div>
                </div>
                <p><button onClick={async () => { const r = await post("/api/admin/run-overdue-check"); if (r) setMsg({ type: "ok", text: `${r.markedOverdue} invoice(s) marked OVERDUE` }); }}>
                  Run overdue check now</button></p>
              </div>
            )}

            <div className="card">
              <h3>Member approvals (KYB)</h3>
              <table>
                <thead><tr><th>ID</th><th>Business</th><th>BR / NIC</th><th>Bank acct</th><th>Email</th><th>Telegram</th><th>Score</th><th>Status</th><th></th></tr></thead>
                <tbody>
                  {vendors.map((v) => (
                    <tr key={v.id}>
                      <td>{v.vendorCode || "-"}</td><td>{v.businessName}</td><td>{v.brNumber}<br />{v.nic}</td>
                      <td>{v.bankAccount}</td><td>{v.email}</td>
                      <td>{v.telegramChatId ? <span className="badge APPROVED">Linked ({v.telegramChatId})</span> : <span className="small">Not linked</span>}</td>
                      <td>{v.trustScore}</td>
                      <td><span className={"badge " + v.status}>{v.status}</span></td>
                      <td className="actions">
                        {v.status !== "APPROVED" && <button className="green" onClick={() => post(`/api/admin/vendors/${v.id}/approve`)}>Approve</button>}
                        {v.status === "APPROVED" && <button className="red" onClick={() => post(`/api/admin/vendors/${v.id}/revoke`)}>Revoke access</button>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <div className="card">
              <h3>Dispute arbitration desk</h3>
              {disputes.length === 0 && <div className="small">No open disputes.</div>}
              {disputes.map((d) => (
                <div key={d.id} className="card" style={{ background: "#f9fafb" }}>
                  <b>{d.invoiceNo}</b> · {money(d.amount)} · Seller: {d.sellerName} → Buyer: {d.buyerName}
                  <div className="small">{d.aiSummary}</div>
                  <div className="actions" style={{ marginTop: 8 }}>
                    <button className="green" onClick={() => post(`/api/admin/invoices/${d.id}/resolve?decision=UPHOLD`)}>Debt is valid</button>
                    <button className="red" onClick={() => post(`/api/admin/invoices/${d.id}/resolve?decision=CANCEL`)}>Cancel (fake bill)</button>
                  </div>
                </div>
              ))}
            </div>
          </>
        )}
      </main>
    </>
  );
}

/* ------------------------------------------------------------------ App shell */
export default function App() {
  const [token, setToken] = useState(load("token"));
  const [admin, setAdmin] = useState(false);

  const login = (t) => { save("token", t); setToken(t); };
  const logout = () => { save("token", null); setToken(null); };

  if (admin) return <Admin onExit={() => setAdmin(false)} />;
  if (token) return <VendorHome token={token} onLogout={logout} />;

  return (
    <>
      <header>
        <h1>Credit-Guard · Pettah Wholesale Association</h1>
        <button className="link" onClick={() => setAdmin(true)}>Admin portal</button>
      </header>
      <main><AuthPage onLogin={login} /></main>
    </>
  );
}
