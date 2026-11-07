import React, { useState } from 'react';

const SchemaInput = ({ schema, onChange }) => {
  const [text, setText] = useState(schema ? JSON.stringify(schema, null, 2) : '');
  const [error, setError] = useState('');
  return (
    <label className="block space-y-1">
      <span>Response JSON Schema (optional)</span>
      <textarea
        rows={6}
        className="w-full p-2 border rounded bg-white dark:bg-zinc-900 font-mono text-xs"
        value={text}
        onChange={(event) => {
          const value = event.target.value;
          setText(value);
          try {
            const parsed = value.trim() ? JSON.parse(value) : undefined;
            if (parsed !== undefined && typeof parsed !== 'boolean' && (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed))) {
              throw new Error('Use a JSON Schema object or boolean');
            }
            onChange(parsed);
            setError('');
          } catch (err) {
            setError(err.message);
          }
        }}
      />
      {error && <span className="text-rose-500">{error} (last valid schema retained)</span>}
    </label>
  );
};
export default SchemaInput;
