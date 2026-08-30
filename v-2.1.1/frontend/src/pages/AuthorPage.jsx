import { useState, useCallback } from 'react';
import QuestionList from '../components/QuestionList';
import QuestionEditor from '../components/editor/QuestionEditor';
import {
  getQuestion,
  saveQuestionContent,
  finalizeQuestion,
  getPdfUrl,
} from '../api';

export default function AuthorPage() {
  const [selectedId, setSelectedId] = useState(null);
  const [question, setQuestion] = useState(null);
  const [saving, setSaving] = useState(false);
  const [status, setStatus] = useState(null);

  const loadQuestion = useCallback(async (id) => {
    setSelectedId(id);
    setStatus(null);
    if (!id) {
      setQuestion(null);
      return;
    }
    try {
      const q = await getQuestion(id);
      setQuestion(q);
    } catch (err) {
      console.error('Failed to load question:', err);
      setStatus({ type: 'error', msg: 'Failed to load question' });
    }
  }, []);

  const handleDocChange = useCallback(
    async (payload) => {
      if (!selectedId || question?.state === 'finalized') return;

      setSaving(true);
      try {
        const updated = await saveQuestionContent(selectedId, payload);
        setQuestion(updated);
      } catch (err) {
        const detail = err.response?.data?.detail || err.message;
        console.error('Failed to save question content:', detail);
        setStatus({ type: 'error', msg: `Save failed: ${detail}` });
      } finally {
        setSaving(false);
      }
    },
    [selectedId, question?.state]
  );

  const handleFinalize = async () => {
    if (!selectedId) return;
    const confirmed = window.confirm(
      'Finalize this question?\n\n' +
        'Content will be permanently frozen. ' +
        'To make changes later, you\'ll need to clone this question into a new draft.'
    );
    if (!confirmed) return;

    try {
      const updated = await finalizeQuestion(selectedId);
      setQuestion(updated);
      setStatus({ type: 'success', msg: 'Question finalized! Content is now locked.' });
    } catch (err) {
      const detail = err.response?.data?.detail || err.message;
      setStatus({ type: 'error', msg: `Finalize failed: ${detail}` });
    }
  };

  const handleExportPdf = () => {
    if (!selectedId) return;
    window.open(getPdfUrl(selectedId), '_blank');
  };

  const isFinalized = question?.state === 'finalized';

  return (
    <div>
      <div className="page-header">
        <h1 className="page-title">✏️ Question Author</h1>
        <p className="page-subtitle">
          Write questions like a document — insert equations and answer boxes inline as you type
        </p>
      </div>

      {status && (
        <div className={`status-msg ${status.type}`}>
          {status.msg}
        </div>
      )}

      <div className="split-layout">
        <div className="stack">
          <QuestionList selectedId={selectedId} onSelect={loadQuestion} />

          {question && (
            <>
              <div className="card">
                <div className="card-title" style={{ marginBottom: 12 }}>Actions</div>
                <div className="btn-group" style={{ flexDirection: 'column' }}>
                  {!isFinalized && (
                    <button
                      className="btn btn-success"
                      onClick={handleFinalize}
                      style={{ width: '100%' }}
                    >
                      🔒 Finalize Question
                    </button>
                  )}
                  {isFinalized && (
                    <button
                      className="btn btn-primary"
                      onClick={handleExportPdf}
                      style={{ width: '100%' }}
                    >
                      📄 Export PDF
                    </button>
                  )}
                </div>
                {saving && (
                  <p style={{ fontSize: 11, color: 'var(--text-muted)', marginTop: 8 }}>
                    <span className="spinner" style={{ width: 10, height: 10, borderWidth: 1.5 }} /> Saving…
                  </p>
                )}
              </div>
            </>
          )}
        </div>

        <div>
          {question ? (
            <QuestionEditor
              key={question.question_id}
              question={question}
              onDocChange={handleDocChange}
            />
          ) : (
            <div className="card" style={{ textAlign: 'center', padding: '60px 20px' }}>
              <p style={{ fontSize: 48, marginBottom: 12 }}>📋</p>
              <p style={{ color: 'var(--text-secondary)' }}>
                Select a question from the list or create a new one
              </p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
