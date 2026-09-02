import { Node, mergeAttributes } from '@tiptap/core';
import { ReactNodeViewRenderer, NodeViewWrapper } from '@tiptap/react';
import { useState, useEffect, useRef } from 'react';
import katex from 'katex';
import 'katex/dist/katex.min.css';

function EquationView({ node, updateAttributes, editor, selected }) {
  const { latex, display } = node.attrs;
  const [editing, setEditing] = useState(!latex);
  const [draft, setDraft] = useState(latex || '');
  const [error, setError] = useState(false);
  const renderRef = useRef(null);
  const canEdit = editor.isEditable;

  useEffect(() => {
    if (editing || !renderRef.current) return;
    try {
      katex.render(latex || '\\text{empty}', renderRef.current, {
        throwOnError: true,
        displayMode: display,
      });
      setError(false);
    } catch {
      renderRef.current.textContent = latex || '(empty equation)';
      setError(true);
    }
  }, [latex, display, editing]);

  const commit = () => {
    updateAttributes({ latex: draft });
    setEditing(false);
  };

  if (editing && canEdit) {
    return (
      <NodeViewWrapper as={display ? 'div' : 'span'} className="eq-node eq-node-editing" style={{ display: display ? 'block' : 'inline-block' }}>
        <input
          autoFocus
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onBlur={commit}
          onKeyDown={(e) => {
            if (e.key === 'Enter') { e.preventDefault(); commit(); }
            if (e.key === 'Escape') { setDraft(latex || ''); setEditing(false); }
          }}
          placeholder="e.g. x^2 + y^2 = r^2"
          style={{
            font: '15px "Fira Code", monospace',
            color: '#7c3aed',
            background: 'rgba(168,85,247,0.08)',
            border: '1.5px dashed #a855f7',
            borderRadius: 4,
            padding: '2px 6px',
            minWidth: 120,
            outline: 'none',
          }}
        />
      </NodeViewWrapper>
    );
  }

  return (
    <NodeViewWrapper
      as={display ? 'div' : 'span'}
      className={`eq-node ${selected ? 'eq-node-selected' : ''}`}
      style={{
        display: display ? 'block' : 'inline-block',
        textAlign: display ? 'center' : 'left',
        margin: display ? '8px 0' : 0,
        padding: '2px 4px',
        borderRadius: 4,
        border: error ? '1px solid #ef4444' : selected ? '1px solid #a855f7' : '1px solid transparent',
        cursor: canEdit ? 'text' : 'default',
      }}
      onClick={() => canEdit && setEditing(true)}
      title={canEdit ? 'Click to edit LaTeX' : undefined}
    >
      <span ref={renderRef} />
    </NodeViewWrapper>
  );
}

export const EquationNode = Node.create({
  name: 'equation',
  group: 'inline',
  inline: true,
  atom: true,
  selectable: true,
  draggable: true,

  addAttributes() {
    return {
      latex: { default: '' },
      display: { default: false },
    };
  },

  parseHTML() {
    return [{ tag: 'span[data-equation]' }];
  },

  renderHTML({ HTMLAttributes }) {
    return ['span', mergeAttributes(HTMLAttributes, { 'data-equation': '' })];
  },

  addNodeView() {
    return ReactNodeViewRenderer(EquationView);
  },

  addCommands() {
    return {
      insertEquation:
        (display = false) =>
        ({ commands }) =>
          commands.insertContent({ type: this.name, attrs: { latex: '', display } }),
    };
  },
});

export default EquationNode;
