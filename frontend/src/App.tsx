import { NavLink, Route, Routes } from 'react-router-dom';
import Help from './pages/Help';
import Home from './pages/Home';
import LLMChat from './pages/LLMChat';
import Flows from './pages/Flows';
import FlowDefinitions from './pages/FlowDefinitions';
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
        <NavLink to="/llm-chat" className="nav-link">
          LLM Chat
        </NavLink>
        <NavLink to="/flows" className="nav-link">
          Flows
        </NavLink>
        <NavLink to="/flow-definitions" className="nav-link">
          Flow Definitions
        </NavLink>
      </nav>
    </header>
    <main className="app-content">
      <Routes>
        <Route path="/" element={<Home />} />
        <Route path="/help" element={<Help />} />
        <Route path="/llm-chat" element={<LLMChat />} />
        <Route path="/flows" element={<Flows />} />
        <Route path="/flow-definitions" element={<FlowDefinitions />} />
      </Routes>
    </main>
  </div>
);

export default App;
