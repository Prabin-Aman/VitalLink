import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { App } from "./App";
import { ApiProvider } from "./data";
import "./styles/global.css";
import { applyTheme, storedTheme } from "./theme";

const theme = storedTheme();
if (theme) applyTheme(theme, false);

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <ApiProvider>
      <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <App />
      </BrowserRouter>
    </ApiProvider>
  </StrictMode>,
);
