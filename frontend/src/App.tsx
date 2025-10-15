import { NavLink, Route, Routes } from 'react-router-dom';
import Help from './pages/Help';
import Home from './pages/Home';
import './App.css';

const App = () => (
  <div className="app">
    <header className="app-header">
      <h1>AI Advent Challenge</h1>
      <nav>
        <NavLink to="/" end className="nav-link">
          Главная
        </NavLink>
        <NavLink to="/help" className="nav-link">
          Help
        </NavLink>
      </nav>
    </header>
    <main className="app-content">
      <Routes>
        <Route path="/" element={<Home />} />
        <Route path="/help" element={<Help />} />
      </Routes>
    </main>
  </div>
);

export default App;
