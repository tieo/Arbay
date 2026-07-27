import React, { useCallback, useEffect, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { Excalidraw, MainMenu } from "@excalidraw/excalidraw";
import "@excalidraw/excalidraw/index.css";
import "./style.css";

const SAVE_AFTER_IDLE_MS = 1200;

async function api(path, options) {
  const response = await fetch(`api/${path}`, options);
  if (!response.ok) throw new Error(`${options?.method ?? "GET"} ${path}: ${response.status}`);
  return response.json();
}

function Model() {
  const [boards, setBoards] = useState([]);
  const [current, setCurrent] = useState(null);
  const [scene, setScene] = useState(null);
  const [saved, setSaved] = useState("loaded");
  const timer = useRef(null);
  const shot = useRef(null);
  const [editor, setEditor] = useState(null);

  useEffect(() => {
    api("boards").then((list) => {
      setBoards(list);
      const wanted = decodeURIComponent(location.hash.slice(1));
      setCurrent(list.find((b) => b.name === wanted)?.name ?? list[0]?.name ?? null);
    });
  }, []);

  useEffect(() => {
    if (!current) return;
    setScene(null);
    location.hash = encodeURIComponent(current);
    api(`boards/${current}`).then((data) => {
      shot.current = JSON.stringify(data.elements);
      setScene(data);
      setSaved("loaded");
    });
  }, [current]);

  // A board opens showing the whole board, since it is a plan to look at before it is one to edit.
  // Fitting is deferred a frame: it measures the canvas, which has no size until the scene mounts.
  useEffect(() => {
    if (!editor || !scene) return;
    // Fit, then drop the view: the toolbar floats over the top of the canvas and would otherwise
    // cover the board's own title.
    const fit = () => {
      editor.scrollToContent(editor.getSceneElements(), { fitToContent: true, animate: false });
      const { scrollY, zoom } = editor.getAppState();
      editor.updateScene({ appState: { scrollY: scrollY + 90 / zoom.value } });
    };
    const frame = requestAnimationFrame(fit);
    const settle = setTimeout(fit, 300);
    return () => {
      cancelAnimationFrame(frame);
      clearTimeout(settle);
    };
  }, [editor, scene]);

  // The file on disk is the document. Every change is written back after a pause, so a board edited
  // here is a diff in the repository rather than a state only this browser knows about.
  const onChange = useCallback(
    (elements, appState, files) => {
      if (!current || !scene) return;
      const serialized = JSON.stringify(elements);
      if (serialized === shot.current) return;
      shot.current = serialized;
      setSaved("saving");
      clearTimeout(timer.current);
      timer.current = setTimeout(() => {
        api(`boards/${current}`, {
          method: "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            type: "excalidraw",
            version: 2,
            source: "arbay-model",
            elements,
            appState: {
              viewBackgroundColor: appState.viewBackgroundColor,
              gridSize: appState.gridSize,
            },
            files,
          }),
        })
          .then(() => setSaved("saved"))
          .catch((error) => setSaved(`not saved: ${error.message}`));
      }, SAVE_AFTER_IDLE_MS);
    },
    [current, scene],
  );

  return (
    <div className="model">
      <aside>
        <h1>The model</h1>
        <p className="hint">Every view of Arbay. Edits are written to the file in the repository.</p>
        <nav>
          {boards.map((board) => (
            <button
              key={board.name}
              className={board.name === current ? "on" : ""}
              onClick={() => setCurrent(board.name)}
            >
              {board.title}
            </button>
          ))}
        </nav>
        <p className={`state ${saved.startsWith("not saved") ? "bad" : ""}`}>{saved}</p>
      </aside>
      <main>
        {scene && (
          <Excalidraw
            key={current}
            initialData={scene}
            excalidrawAPI={setEditor}
            onChange={onChange}
            UIOptions={{ canvasActions: { loadScene: false, saveToActiveFile: false } }}
          >
            <MainMenu>
              <MainMenu.DefaultItems.ToggleTheme />
              <MainMenu.DefaultItems.ChangeCanvasBackground />
              <MainMenu.DefaultItems.SaveAsImage />
            </MainMenu>
          </Excalidraw>
        )}
      </main>
    </div>
  );
}

createRoot(document.getElementById("root")).render(<Model />);
