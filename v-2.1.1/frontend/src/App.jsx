import { BrowserRouter, Routes, Route, NavLink } from 'react-router-dom';
import AuthorPage from './pages/AuthorPage';
import ExtractPage from './pages/ExtractPage';
import './App.css';

function App() {
  return (
    <BrowserRouter>
      <div className="app-shell">
        <nav className="top-nav">
          <div className="nav-brand">NLP-OCR Evaluator</div>
          <div className="nav-links">
            <NavLink to="/" end className={({ isActive }) => isActive ? 'nav-link active' : 'nav-link'}>
              ✏️ Question Author
            </NavLink>
            <NavLink to="/extract" className={({ isActive }) => isActive ? 'nav-link active' : 'nav-link'}>
              🔍 Extraction Test
            </NavLink>
          </div>
        </nav>
        <main className="main-content">
          <Routes>
            <Route path="/" element={<AuthorPage />} />
            <Route path="/extract" element={<ExtractPage />} />
          </Routes>
        </main>
      </div>
    </BrowserRouter>
  );
}

export default App;
