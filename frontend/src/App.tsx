import { NavLink, Route, Routes } from 'react-router-dom';
import Help from './pages/Help';
import Home from './pages/Home';
import LLMChat from './pages/LLMChat';
import FlowDefinitions from './pages/FlowDefinitions';
import FlowLaunch from './pages/FlowLaunch';
import FlowSessions from './pages/FlowSessions';
import FlowsLayout from './pages/FlowsLayout';
import FlowsOverview from './pages/FlowsOverview';
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
      </nav>
    </header>
    <main className="app-content">
      <Routes>
        <Route path="/" element={<Home />} />
        <Route path="/help" element={<Help />} />
        <Route path="/llm-chat" element={<LLMChat />} />
        <Route path="/flows" element={<FlowsLayout />}>
          <Route index element={<FlowsOverview />} />
          <Route path="launch" element={<FlowLaunch />} />
          <Route path="sessions" element={<FlowSessions />} />
          <Route path="sessions/:sessionId" element={<FlowSessions />} />
          <Route path="definitions" element={<FlowDefinitions />} />
          <Route path="definitions/:definitionId" element={<FlowDefinitions />} />
        </Route>
      </Routes>
    </main>
  </div>
);

export default App;
