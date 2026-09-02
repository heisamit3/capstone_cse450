import { useState, useRef, useEffect } from 'react';
import { submitImage, getCropUrl } from '../api';

export default function ExtractionView({ question }) {
  const [modality, setModality] = useState('photo');
  const [pageIndex, setPageIndex] = useState(0);
  const [file, setFile] = useState(null);
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);
  const fileRef = useRef(null);

  useEffect(() => {
    setPageIndex(0);
    setResult(null);
    setFile(null);
  }, [question?.question_id]);

  const handleExtract = async () => {
    if (!question || !file) return;
    setLoading(true);
    setError(null);
    setResult(null);

    try {
      const data = await submitImage(question.question_id, modality, file, pageIndex);
      setResult(data);
    } catch (err) {
      const detail = err.response?.data?.detail || err.message;
      setError(detail);
    } finally {
      setLoading(false);
    }
  };

  if (!question) {
    return (
      <div className="card" style={{ textAlign: 'center', padding: '40px 20px' }}>
        <p style={{ color: 'var(--text-secondary)' }}>
          Select a finalized question to test extraction
        </p>
      </div>
    );
  }

  if (question.state !== 'finalized') {
    return (
      <div className="card" style={{ textAlign: 'center', padding: '40px 20px' }}>
        <p style={{ color: 'var(--warning)' }}>
          ⚠️ This question is still a draft. Finalize it first before testing extraction.
        </p>
      </div>
    );
  }

  const answerBoxes = question.answer_boxes || [];
  const pageCount = question.page_count || 1;

  return (
    <div className="stack">
      <div className="card">
        <div className="card-title" style={{ marginBottom: 12 }}>Test Submission</div>

        <div className="row" style={{ flexWrap: 'wrap', gap: 16 }}>
          {pageCount > 1 && (
            <div className="form-group" style={{ flex: 1, minWidth: 140, margin: 0 }}>
              <label className="form-label">Page</label>
              <select
                className="form-select"
                value={pageIndex}
                onChange={(e) => setPageIndex(Number(e.target.value))}
              >
                {Array.from({ length: pageCount }, (_, i) => (
                  <option key={i} value={i}>
                    Page {i + 1} of {pageCount}
                  </option>
                ))}
              </select>
            </div>
          )}

          <div className="form-group" style={{ flex: 1, minWidth: 200, margin: 0 }}>
            <label className="form-label">Modality</label>
            <select
              className="form-select"
              value={modality}
              onChange={(e) => setModality(e.target.value)}
            >
              <option value="photo">📱 Phone Photo (full homography)</option>
              <option value="scanner">🖨️ Scanner (affine only)</option>
            </select>
          </div>

          <div className="form-group" style={{ flex: 2, minWidth: 250, margin: 0 }}>
            <label className="form-label">Upload Image</label>
            <input
              ref={fileRef}
              type="file"
              accept="image/jpeg,image/png,image/tiff,application/pdf"
              onChange={(e) => setFile(e.target.files[0] || null)}
              className="form-input"
              style={{ padding: '6px 8px' }}
            />
          </div>

          <div style={{ alignSelf: 'flex-end' }}>
            <button
              className="btn btn-primary"
              onClick={handleExtract}
              disabled={!file || loading}
            >
              {loading ? (
                <>
                  <span className="spinner" /> Extracting…
                </>
              ) : (
                '🔍 Run Extraction'
              )}
            </button>
          </div>
        </div>

        <p style={{ fontSize: 11, color: 'var(--text-muted)', marginTop: 10 }}>
          Question: <code>{question.question_id.slice(0, 8)}…</code> · {answerBoxes.length} answer box(es) total
          {pageCount > 1 ? ` across ${pageCount} pages` : ''}.{' '}
          {pageCount > 1 && 'Upload a multi-page PDF/TIFF to process all pages at once — the page selector above only applies to single-image (JPEG/PNG) uploads.'}
        </p>
      </div>

      {error && (
        <div className="status-msg error">
          ❌ {error}
        </div>
      )}

      {result && (
        <div className="stack" style={{ gap: 20 }}>
          {result.pages.map((page) => {
            const boxesForPage = answerBoxes.filter((b) => b.page_index === page.page_index);
            return (
              <div key={page.page_index}>
                <div className="summary-bar">
                  <div className="summary-item">
                    <span className="label">Page</span>
                    <span className="value">{page.page_index + 1} of {pageCount}</span>
                  </div>
                  <div className="summary-item">
                    <span className="label">Markers</span>
                    <span className="value" style={{ color: page.markers_detected === '4/4' ? 'var(--success)' : 'var(--danger)' }}>
                      {page.markers_detected}
                    </span>
                  </div>
                  <div className="summary-item">
                    <span className="label">Transform</span>
                    <span className="value">{page.transform_type}</span>
                  </div>
                  <div className="summary-item">
                    <span className="label">Resolution</span>
                    <span className="value">{page.image_resolution || 'N/A'}</span>
                  </div>
                  <div className="summary-item">
                    <span className="label">Crops</span>
                    <span className="value">{page.crops.length}</span>
                  </div>
                </div>

                {page.error && (
                  <div className="status-msg error" style={{ marginTop: 8 }}>⚠️ {page.error}</div>
                )}

                {page.crops.length > 0 && (
                  <div className="card" style={{ marginTop: 8 }}>
                    <div className="card-title" style={{ marginBottom: 16 }}>
                      Page {page.page_index + 1} — Extracted Answer Crops
                    </div>
                    <div className="crop-grid">
                      {page.crops.map((crop) => {
                        const box = boxesForPage.find((b) => b.id === crop.answer_box_id);
                        return (
                          <div key={crop.answer_box_id} className="crop-card">
                            <img
                              src={getCropUrl(result.submission_id, crop.answer_box_id)}
                              alt={`Crop: ${crop.answer_box_id}`}
                              loading="lazy"
                            />
                            <div className="crop-card-info">
                              <div className="row" style={{ justifyContent: 'space-between' }}>
                                <span className="label">☐ {box?.label || crop.answer_box_id.slice(0, 8)}</span>
                                <span className={`badge badge-${crop.qr_check}`}>
                                  QR: {crop.qr_check}
                                </span>
                              </div>
                              <span style={{ fontSize: 11, color: 'var(--text-muted)', fontFamily: 'monospace' }}>
                                {crop.answer_box_id.slice(0, 12)}…
                              </span>
                              <span style={{ fontSize: 10, color: 'var(--text-muted)' }}>
                                bbox: [{crop.warped_bbox.join(', ')}]
                              </span>
                            </div>
                          </div>
                        );
                      })}
                    </div>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
