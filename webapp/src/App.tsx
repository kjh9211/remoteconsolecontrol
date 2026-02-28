import { useState, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import AnsiConverter from 'ansi-to-html';
import './App.css';

const converter = new AnsiConverter({
  fg: '#FFF',
  bg: '#000',
  newline: false,
  escapeXML: true,
  stream: false
});

function App() {
  const [logs, setLogs] = useState<string[]>([]);
  const [command, setCommand] = useState('');
  const ws = useRef<WebSocket | null>(null);
  const heartbeatInterval = useRef<number | null>(null);
  const logsEndRef = useRef<HTMLDivElement | null>(null);
  const navigate = useNavigate();
  const token = localStorage.getItem('authToken');

  useEffect(() => {
    if (!token) {
      navigate('/login');
      return;
    }

    const connect = () => {
      ws.current = new WebSocket(`ws://${window.location.host}/console?token=${token}`);

      ws.current.onopen = () => {
        console.log('WebSocket connected');
        setLogs(prev => [...prev, converter.toHtml('[SYSTEM] Connection established.')]);
        
        // Start heartbeat
        heartbeatInterval.current = setInterval(() => {
          if (ws.current?.readyState === WebSocket.OPEN) {
            ws.current.send(''); // Send empty message to keep connection alive
          }
        }, 30000); // Every 30 seconds
      };

      ws.current.onmessage = (event) => {
        if (event.data) { // Only log if there's actual data
          setLogs((prevLogs) => [...prevLogs, converter.toHtml(event.data)]);
        }
      };

      ws.current.onclose = (event) => {
        console.log('WebSocket disconnected', event.reason);
        
        // Stop heartbeat
        if (heartbeatInterval.current) {
          clearInterval(heartbeatInterval.current);
        }

        if (event.code === 403) {
             setLogs(prev => [...prev, converter.toHtml('[SYSTEM] Authentication failed. Redirecting to login.')]);
             setTimeout(() => navigate('/login'), 3000);
        } else {
            setLogs(prev => [...prev, converter.toHtml('[SYSTEM] Connection lost. Attempting to reconnect...')]);
            setTimeout(connect, 3000);
        }
      };

      ws.current.onerror = (error) => {
        console.error('WebSocket error:', error);
        ws.current?.close();
      };
    };

    connect();

    return () => {
      ws.current?.close();
    };
  }, [token, navigate]);

  useEffect(() => {
    logsEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [logs]);

  const handleSendCommand = () => {
    if (command && ws.current?.readyState === WebSocket.OPEN) {
      ws.current.send(command);
      setCommand('');
    }
  };

  const handleLogout = () => {
    localStorage.removeItem('authToken');
    navigate('/login');
  };
  
  if (!token) {
    return null; // Or a loading spinner
  }

  return (
    <div className="App">
      <header className="App-header">
        <h1>Remote Console</h1>
        <button onClick={handleLogout} className="logout-button">Logout</button>
      </header>
      <div className="console-container">
        <div className="console-output">
          {logs.map((log, index) => (
            <div key={index} dangerouslySetInnerHTML={{ __html: log }} />
          ))}
          <div ref={logsEndRef} />
        </div>
        <div className="console-input">
          <input
            type="text"
            value={command}
            onChange={(e) => setCommand(e.target.value)}
            onKeyPress={(e) => e.key === 'Enter' && handleSendCommand()}
            placeholder="Enter command..."
          />
          <button onClick={handleSendCommand}>Send</button>
        </div>
      </div>
    </div>
  );
}

export default App;
