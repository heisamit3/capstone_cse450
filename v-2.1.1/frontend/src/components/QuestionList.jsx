import { useState, useEffect, useCallback } from 'react';
import {
  listQuestions,
  createQuestion,
  cloneQuestion,
} from '../api';

export default function QuestionList({ selectedId, onSelect }) {
  const [questions, setQuestions] = useState([]);
  const [loading, setLoading] = useState(false);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const data = await listQuestions();
      setQuestions(data);
    } catch (err) {
      console.error('Failed to load questions:', err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const handleCreate = async () => {
    try {
      const q = await createQuestion({
        physical_page: 'A4',
        content: null,
        answer_boxes: [],
      });
      await refresh();
      onSelect(q.question_id);
    } catch (err) {
      console.error('Failed to create question:', err);
    }
  };

  const handleClone = async (id, e) => {
    e.stopPropagation();
    try {
      const cloned = await cloneQuestion(id);
      await refresh();
      onSelect(cloned.question_id);
    } catch (err) {
      console.error('Failed to clone:', err);
    }
  };

  return (
    <div className="card">
      <div className="card-header">
        <span className="card-title">Questions</span>
        <button className="btn btn-primary btn-sm" onClick={handleCreate}>
          + New
        </button>
      </div>

      {loading && <p style={{ color: 'var(--text-muted)', fontSize: 13 }}>Loading…</p>}

      <div className="stack" style={{ gap: 4 }}>
        {questions.map((q) => (
          <div
            key={q.question_id}
            className={`question-item ${selectedId === q.question_id ? 'selected' : ''}`}
            onClick={() => onSelect(q.question_id)}
          >
            <div className="row" style={{ justifyContent: 'space-between' }}>
              <span className={`badge ${q.state === 'finalized' ? 'badge-finalized' : 'badge-draft'}`}>
                {q.state}
              </span>
              {q.state === 'finalized' && (
                <button
                  className="btn btn-secondary btn-sm"
                  onClick={(e) => handleClone(q.question_id, e)}
                  title="Clone as new draft"
                >
                  Clone
                </button>
              )}
            </div>
            <div className="question-item-id">
              {q.question_id.slice(0, 8)}… · {q.answer_boxes?.length ?? 0} answer box(es)
            </div>
          </div>
        ))}

        {!loading && questions.length === 0 && (
          <p style={{ color: 'var(--text-muted)', fontSize: 13, padding: '12px 0' }}>
            No questions yet. Click <strong>+ New</strong> to create one.
          </p>
        )}
      </div>
    </div>
  );
}
