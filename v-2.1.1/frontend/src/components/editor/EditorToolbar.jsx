export default function EditorToolbar({ editor, isFinalized }) {
  if (!editor) return null;

  const Btn = ({ onClick, active, disabled, title, children }) => (
    <button
      type="button"
      className={`btn btn-sm ${active ? 'btn-primary' : 'btn-secondary'}`}
      onMouseDown={(e) => e.preventDefault()}
      onClick={onClick}
      disabled={disabled || isFinalized}
      title={title}
    >
      {children}
    </button>
  );

  return (
    <div
      className="doc-toolbar"
      style={{
        position: 'sticky',
        top: 0,
        zIndex: 10,
        display: 'flex',
        flexWrap: 'wrap',
        gap: 6,
        padding: '8px 12px',
        background: 'var(--bg-card, #fff)',
        borderBottom: '1px solid var(--border, #e5e5e5)',
      }}
    >
      <Btn onClick={() => editor.chain().focus().toggleBold().run()} active={editor.isActive('bold')} title="Bold">
        B
      </Btn>
      <Btn onClick={() => editor.chain().focus().toggleItalic().run()} active={editor.isActive('italic')} title="Italic">
        i
      </Btn>
      <Btn onClick={() => editor.chain().focus().toggleHeading({ level: 2 }).run()} active={editor.isActive('heading', { level: 2 })} title="Heading">
        H
      </Btn>

      <select
        onMouseDown={(e) => e.stopPropagation()}
        onChange={(e) => {
          const val = e.target.value;
          if (val === 'default') editor.chain().focus().unsetFontSize().run();
          else editor.chain().focus().setFontSize(val).run();
        }}
        defaultValue="default"
        disabled={isFinalized}
        style={{ fontSize: 12, border: '1px solid var(--border, #ddd)', borderRadius: 4, padding: '2px 4px' }}
        title="Font size"
      >
        <option value="default">Size: Normal</option>
        <option value="12px">Small</option>
        <option value="18px">Large</option>
        <option value="22px">X-Large</option>
        <option value="28px">Heading</option>
      </select>

      <span style={{ width: 1, background: 'var(--border, #e5e5e5)', margin: '0 4px' }} />

      <Btn onClick={() => editor.chain().focus().insertEquation(false).run()} title="Insert inline equation">
        ∑ Inline Eq
      </Btn>
      <Btn onClick={() => editor.chain().focus().insertEquation(true).run()} title="Insert centered equation">
        ∑ Block Eq
      </Btn>
      <Btn onClick={() => editor.chain().focus().insertAnswerBox('').run()} title="Insert answer box for students">
        ☐ Answer Box
      </Btn>

      {isFinalized && (
        <span className="badge badge-finalized" style={{ marginLeft: 'auto' }}>
          🔒 Finalized — Read Only
        </span>
      )}
    </div>
  );
}
