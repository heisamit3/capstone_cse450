import axios from 'axios';

const api = axios.create({
  baseURL: 'http://localhost:8000/api',
  timeout: 60000,
});

export const createQuestion = (data) =>
  api.post('/questions', data).then((r) => r.data);

export const listQuestions = () =>
  api.get('/questions').then((r) => r.data);

export const getQuestion = (id) =>
  api.get(`/questions/${id}`).then((r) => r.data);

export const saveQuestionContent = (id, payload) =>
  api.put(`/questions/${id}/blocks`, payload).then((r) => r.data);

export const finalizeQuestion = (id) =>
  api.post(`/questions/${id}/finalize`).then((r) => r.data);

export const getPdfUrl = (id) =>
  `http://localhost:8000/api/questions/${id}/pdf`;

export const cloneQuestion = (id) =>
  api.post(`/questions/${id}/clone`).then((r) => r.data);

export const submitImage = (questionId, modality, imageFile, pageIndex = 0) => {
  const formData = new FormData();
  formData.append('question_id', questionId);
  formData.append('modality', modality);
  formData.append('page_index', pageIndex);
  formData.append('image', imageFile);
  return api.post('/submissions', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  }).then((r) => r.data);
};

export const submitTablet = (questionId, inkStrokes) =>
  api.post('/submissions/tablet', {
    question_id: questionId,
    ink_strokes: inkStrokes,
  }).then((r) => r.data);

export const getSubmission = (id) =>
  api.get(`/submissions/${id}`).then((r) => r.data);

export const getCropUrl = (submissionId, blockId) =>
  `http://localhost:8000/api/submissions/${submissionId}/crops/${blockId}`;

export default api;
