import { useEffect, useRef } from 'react';
import { useEditor, EditorContent } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import Placeholder from '@tiptap/extension-placeholder';
import EquationNode from './extensions/EquationNode';
import AnswerBoxNode from './extensions/AnswerBoxNode';
import FontSize from './extensions/FontSize';
import EditorToolbar from './EditorToolbar';

export default function QuestionEditor({ question, onDocChange }) {
  const isFinalized = question?.state === 'finalized';
  const loadedIdRef = useRef(null);
  const debounceRef = useRef(null);

  const editor = useEditor({
    editable: !isFinalized,
    extensions: [
      StarterKit.configure({
        heading: { levels: [1, 2, 3] },
        bulletList: false,
        orderedList: false,
        listItem: false,
      }),
      Placeholder.configure({
        placeholder: 'Start typing the question… use the toolbar to insert equations or answer boxes.',
      }),
      EquationNode,
      AnswerBoxNode,
      FontSize,
    ],
    content: question?.content || '',
    onUpdate: ({ editor }) => {
      if (!onDocChange) return;
      clearTimeout(debounceRef.current);
      debounceRef.current = setTimeout(() => {
        onDocChange(_extractPayload(editor));
      }, 500);
    },
  });

  useEffect(() => {
    if (!editor || !question) return;
    if (loadedIdRef.current === question.question_id) return;
    loadedIdRef.current = question.question_id;
    editor.commands.setContent(question.content || '', false);
    editor.setEditable(!isFinalized);
  }, [editor, question, isFinalized]);

  useEffect(() => {
    editor?.setEditable(!isFinalized);
  }, [editor, isFinalized]);

  if (!editor) return null;

  return (
    <div className="doc-editor-shell">
      <EditorToolbar editor={editor} isFinalized={isFinalized} />
      <div className="doc-editor-scroll">
        <div className="doc-page">
          <EditorContent editor={editor} />
        </div>
      </div>
    </div>
  );
}

function _extractPayload(editor) {
  const json = editor.getJSON();
  const answerBoxes = [];
  editor.state.doc.descendants((node) => {
    if (node.type.name === 'answerBox') {
      answerBoxes.push({ id: node.attrs.id, label: node.attrs.label, points: node.attrs.points });
    }
  });
  return { content: json, answer_boxes: answerBoxes };
}
