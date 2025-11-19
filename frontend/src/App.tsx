import { NavLink, Route, Routes } from 'react-router-dom';
import Help from './pages/Help';
import Home from './pages/Home';
import LLMChat from './pages/LLMChat';
import FlowDefinitions from './pages/FlowDefinitions';
import FlowLaunch from './pages/FlowLaunch';
import FlowSessions from './pages/FlowSessions';
import FlowsLayout from './pages/FlowsLayout';
import FlowsOverview from './pages/FlowsOverview';
import FlowAgents from './pages/FlowAgents';
import ProfileSettings from './pages/ProfileSettings';
import AdminRoles from './pages/AdminRoles';
import { useProfileStore } from './lib/profileStore';
import './App.css';

const App = () => {
  const hasAdminRole = useProfileStore(
    (state) => !!state.profile?.roles?.some((role) => role === 'admin'),
  );

  return (
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
          <NavLink to="/profile" className="nav-link">
            Personalization
          </NavLink>
          {hasAdminRole && (
            <NavLink to="/admin/roles" className="nav-link">
              Admin
            </NavLink>
          )}
        </nav>
      </header>
      <main className="app-content">
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/help" element={<Help />} />
          <Route path="/llm-chat" element={<LLMChat />} />
          <Route path="/profile" element={<ProfileSettings />} />
          <Route path="/admin/roles" element={<AdminRoles />} />
          <Route path="/flows" element={<FlowsLayout />}>
            <Route index element={<FlowsOverview />} />
            <Route path="launch" element={<FlowLaunch />} />
            <Route path="sessions" element={<FlowSessions />} />
            <Route path="sessions/:sessionId" element={<FlowSessions />} />
            <Route path="agents" element={<FlowAgents />} />
            <Route path="definitions" element={<FlowDefinitions />} />
            <Route path="definitions/:definitionId" element={<FlowDefinitions />} />
          </Route>
        </Routes>
      </main>
    </div>
  );
};

export default App;
