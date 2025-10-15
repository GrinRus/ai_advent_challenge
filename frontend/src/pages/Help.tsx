import { useEffect, useState } from 'react';
import { fetchHelp, type HelpResponse } from '../lib/apiClient';
import './Help.css';

const Help = () => {
  const [data, setData] = useState<HelpResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let abort = false;

    fetchHelp()
      .then((response) => {
        if (abort) return;
        setData(response);
        setError(null);
      })
      .catch((err) => {
        if (abort) return;
        setError(err instanceof Error ? err.message : String(err));
      })
      .finally(() => {
        if (!abort) setLoading(false);
      });

    return () => {
      abort = true;
    };
  }, []);

  return (
    <div className="page">
      <h1>Help</h1>
      {loading && <p>Загрузка подсказки...</p>}
      {error && (
        <p role="alert" className="error">
          Не удалось получить текст: {error}
        </p>
      )}
      {data && (
        <div className="help-card">
          <h2>Подсказка от backend</h2>
          <p>{data.message}</p>
        </div>
      )}
    </div>
  );
};

export default Help;
