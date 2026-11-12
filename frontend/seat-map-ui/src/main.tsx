import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import './styles.css';

/**
 * The entry point of the demo client.
 *
 * Strict mode on purpose, in development as well. It runs every effect twice, which is exactly the
 * pressure the live seat map should be under: a hook that opened a stream and did not close it
 * again would leave two connections per page, and this is the cheapest place to find that out.
 */
const root = document.getElementById('root');
if (root === null) {
  throw new Error('index.html has no #root to mount into');
}

createRoot(root).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
