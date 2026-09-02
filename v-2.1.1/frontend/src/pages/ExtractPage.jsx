import { useState, useEffect } from 'react';
import { listQuestions, getQuestion } from '../api';
import ExtractionView from '../components/ExtractionView';

export default function ExtractPage() {
  const [questions, setQuestions] = useState([]);
  const [selectedId, setSelectedId] = useState('');
  const [question, setQuestion] = useState(null);

  useEffect(() => {
    listQuestions()
      .then((data) => {
        setQuestions(data.filter((q) => q.state === 'finalized'));
      })
      .catch(console.error);
  }, []);

  useEffect(() => {
    if (!selectedId) {
      setQuestion(null);
      return;
    }
    getQuestion(selectedId)
      .then(setQuestion)
      .catch(console.error);
  }, [selectedId]);

  return (
    <div>
      <div className="page-header">
        <h1 className="page-title">🔍 Extraction Test</h1>
        <p className="page-subtitle">
          Upload a scanned/photographed page and test the deterministic answer-box extraction pipeline
        </p>
      </div>

      <div className="card" style={{ marginBottom: 20 }}>
        <div className="form-group" style={{ margin: 0 }}>
          <label className="form-label">Select Finalized Question</label>
          <select
            className="form-select"
            value={selectedId}
            onChange={(e) => setSelectedId(e.target.value)}
          >
            <option value="">— Choose a question —</option>
            {questions.map((q) => (
              <option key={q.question_id} value={q.question_id}>
                {q.question_id.slice(0, 8)}… · {q.answer_boxes?.length ?? 0} answer box(es) · {q.physical_page}
                {q.page_count > 1 ? ` · ${q.page_count} pages` : ''}
              </option>
            ))}
          </select>
        </div>

        {questions.length === 0 && (
          <p style={{ fontSize: 13, color: 'var(--text-muted)', marginTop: 10 }}>
            No finalized questions available. Go to the{' '}
            <a href="/" style={{ color: 'var(--accent)' }}>Question Author</a>{' '}
            to create and finalize a question first.
          </p>
        )}
      </div>

      <ExtractionView question={question} />
    </div>
  );
}
