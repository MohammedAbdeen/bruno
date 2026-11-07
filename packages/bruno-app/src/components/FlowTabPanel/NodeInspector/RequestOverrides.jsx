import React, { useState } from 'react';
import toast from 'react-hot-toast';

const MapEditor = ({ label, value = {}, onChange }) => {
  const [rows, setRows] = useState(() => Object.entries(value).map(([name, value]) => ({ name, value: String(value) })));
  const update = (next) => {
    setRows(next);
    onChange(Object.fromEntries(next.filter((row) => row.name.trim()).map((row) => [row.name.trim(), row.value])));
  };
  return (
    <section className="space-y-2">
      <h3>{label}</h3>
      {rows.map((row, index) => (
        <div className="flex gap-1" key={index}>
          <input aria-label={`${label} name ${index + 1}`} value={row.name} placeholder="Name" className="w-1/3 p-1 border rounded bg-transparent" onChange={(event) => update(rows.map((entry, i) => i === index ? { ...entry, name: event.target.value } : entry))} />
          <input aria-label={`${label} value ${index + 1}`} value={row.value} placeholder="Value or {{variable}}" className="w-1/2 p-1 border rounded bg-transparent" onChange={(event) => update(rows.map((entry, i) => i === index ? { ...entry, value: event.target.value } : entry))} />
          <button aria-label={`Remove ${label} ${index + 1}`} onClick={() => update(rows.filter((_, i) => i !== index))}>×</button>
        </div>
      ))}
      <button className="border rounded p-1" onClick={() => setRows([...rows, { name: '', value: '' }])}>Add {label}</button>
    </section>
  );
};

const RequestOverrides = ({ overrides = {}, onChange, isRunning }) => {
  const [body, setBody] = useState(() => overrides.body === undefined ? '' : JSON.stringify(overrides.body, null, 2));
  const [error, setError] = useState('');
  return (
    <fieldset disabled={isRunning} className="space-y-4">
      <p>These values apply to this step before interpolation and request scripts. The saved request is preserved.</p>
      <MapEditor label="Header" value={overrides.headers} onChange={(headers) => onChange({ ...overrides, headers })} />
      <MapEditor label="Query parameter" value={overrides.params} onChange={(params) => onChange({ ...overrides, params })} />
      <label className="block">JSON body fields (shallow merge)
        <textarea
          aria-label="Body overrides"
          rows={6}
          className="w-full p-2 border rounded bg-transparent font-mono"
          value={body}
          onChange={(event) => {
            setBody(event.target.value); setError('');
          }}
        />
      </label>
      <button
        className="border rounded p-1"
        onClick={() => {
          try {
            const next = { ...overrides };
            if (!body.trim()) delete next.body;
            else {
              const parsed = JSON.parse(body);
              if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') throw new Error('Body overrides must be a JSON object');
              next.body = parsed;
            }
            onChange(next); setError(''); toast.success('Body overrides applied');
          } catch (err) { setError(err.message); }
        }}
      >Apply body overrides
      </button>
      {error && <p role="status">{error}</p>}
      <p>Body fields support JSON object requests. Header and query values may use {'{{flowVariable}}'}.</p>
    </fieldset>
  );
};
export default RequestOverrides;
