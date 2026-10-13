import { createBrowserRouter } from 'react-router';

function Home() {
  return <h1 className="p-6 text-2xl font-bold">OTT</h1>;
}

export const router = createBrowserRouter([{ path: '/', Component: Home }]);
