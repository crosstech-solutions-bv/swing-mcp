# Interaction tools

Tools for interacting with components in the active window. All UID parameters
come from `take_snapshot` (see [inspection.md](inspection.md)). Actions are
executed on the owning toolkit's UI thread: the Event Dispatch Thread for
Swing, the JavaFX Application Thread for JavaFX.

## `click`

Click a component. `AbstractButton`s (buttons, checkboxes, radio buttons,
toggle buttons) are clicked directly via `doClick()`; other components via
mouse emulation.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `uid` | string | yes | Component UID |
| `button` | string | no | `LEFT` (default), `RIGHT`, `MIDDLE` |
| `clickType` | string | no | `SINGLE` (default) or `DOUBLE` |

## `hover`

Move the mouse over a component to trigger hover effects and tooltips
(mouse-emulation move without a click).

| Parameter | Type | Required | Description |
|---|---|---|---|
| `uid` | string | yes | Component UID |

## `focus`

Give keyboard focus to a component so that subsequent `press_key` or
`type_text` calls target it deterministically.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `uid` | string | yes | Component UID |

## `type_text`

Type text character-by-character into the focused component using key events
(as opposed to `fill`, which sets the value directly). Use this for UIs with
per-keystroke listeners, input masks, or autocompletion.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `text` | string | yes | Text to type |
| `uid` | string | no | Component UID to focus before typing |
| `robot` | boolean | no | Swing: send OS-level keystrokes with `java.awt.Robot`; see `press_key` |

**Notes:** each character is delivered as key pressed/typed/released events
straight to the component, one UI-thread turn per character, so listeners run
between keystrokes. Any character can be typed, whatever the keyboard layout.
See `press_key` for where the keys go.

## `fill`

Set the text/value of a text component (`JTextField`, `JTextArea`, …),
`JSpinner`, `JSlider`, or editable `JComboBox` (Swing) — or a
`TextInputControl` (`TextField`, `PasswordField`, `TextArea`), `Spinner`, or
editable `ComboBox` (JavaFX).

| Parameter | Type | Required | Description |
|---|---|---|---|
| `uid` | string | yes | Component UID |
| `text` | string | yes | Text or value to enter |

**Notes:** a numeric `JSpinner` receives a value of its model's own number
class (`Integer`, `Long`, `Double`, …), as if the user had typed it. A
fraction for an integer spinner, or a value outside the spinner's
minimum/maximum, is rejected with an error instead of being set.

## `select_option`

Select an option in a `JList`, `JComboBox`, or `JTabbedPane` (Swing) — or a
`ComboBox`, `ChoiceBox`, `ListView`, or `TabPane` (JavaFX) — by index or
visible text.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `uid` | string | yes | Component UID |
| `index` | number | no | Zero-based option index |
| `text` | string | no | Visible text of the option |

**Notes:** provide either `index` or `text`.

## `select_tree_node`

Select a `JTree` (Swing) or `TreeView` (JavaFX) node by path.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `uid` | string | yes | `JTree` (Swing) or `TreeView` (JavaFX) component UID |
| `path` | string | yes | Node path separated by ` > `, e.g. `Root > Folder > Leaf` |

## `select_table_cell`

Select a `JTable` (Swing) or `TableView` (JavaFX) cell, like a mouse click on
it, or several rows.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `uid` | string | yes | `JTable` (Swing) or `TableView` (JavaFX) component UID |
| `row` | number | no | Zero-based row index; required unless `rows` is given |
| `col` | number | no | Zero-based column index (default 0) |
| `toggle` | boolean | no | Add the row to, or remove it from, the selection (Ctrl/Cmd-click) |
| `extend` | boolean | no | Select the range from the anchor row to `row` (Shift-click) |
| `rows` | number[] | no | Rows that become the whole selection; `[]` clears it |

**Returns:** the resulting selection, e.g. `Table selection: rows [1, 3], column 0`.

**Notes:**
- `toggle` and `extend` map to `JTable.changeSelection(row, col, toggle, extend)`
  in Swing. In JavaFX the anchor for `extend` is the focused cell.
- `rows` cannot be combined with `toggle` or `extend`.
- A table in single-selection mode keeps one row; the result says so.

## `select_menu_item`

Click a menu item in the active window's menu bar (`JMenuBar` for Swing,
`MenuBar` for JavaFX).

| Parameter | Type | Required | Description |
|---|---|---|---|
| `path` | string | yes | Menu path separated by ` > `, e.g. `File > Save` |

## `select_context_menu_item`

Open the context menu of a component and click an item by path
(`JPopupMenu` for Swing, `ContextMenu` for JavaFX). The component's
registered popup menu is used when available; otherwise a right-click popup
trigger is dispatched.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `uid` | string | yes | Component UID |
| `path` | string | yes | Menu item path separated by ` > `, e.g. `Copy` or `Refactor > Rename` |

## `press_key`

Press a key or key chord in the target application.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `keys` | string | yes | Key or chord such as `ENTER`, `TAB`, `ESCAPE`, `F5`, `HOME`, `PAGE_DOWN`, `CTRL+S`, `SHORTCUT+A` |
| `uid` | string | no | Component to focus and send the keys to |
| `robot` | boolean | no | Swing: send OS-level keystrokes with `java.awt.Robot` instead (see below) |

**Returns:** a confirmation naming the component that received the keys, or a
pending result when the key opened a modal dialog (as for `click`).

**Notes:**
- Keys are delivered as key events straight to the application: to `uid`
  when given, else to the component that has keyboard focus in the
  application, else to what would get focus in the open modal dialog or the
  active window. This works while the application is in the background and the
  keys can never end up in another application. Focus traversal (`TAB`), key
  bindings, menu accelerators and default buttons behave as for real input.
- Key names are modifier names (`CTRL`, `SHIFT`, `ALT`, `META`/`CMD`,
  `SHORTCUT` for Ctrl or Cmd on macOS), any `KeyEvent.VK_*` name without the
  prefix (`F5`, `HOME`, `END`, `PAGE_UP`, `INSERT`, …), or a single character,
  joined with `+`.
- `robot: true` sends real keystrokes to whatever application is in front, so
  the target window is first brought to the front; if that fails (as it can on
  macOS or Windows for a background application), the call fails instead of
  typing into another application.

## `drag`

Drag from one component to another using mouse emulation.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `fromUid` | string | yes | Source component UID |
| `toUid` | string | yes | Target component UID |

**Notes:**
- Swing only; on a JavaFX uid this fails with an error saying so.

## `scroll`

Scroll a component inside a `JScrollPane` (Swing) or `ScrollPane` (JavaFX).

| Parameter | Type | Required | Description |
|---|---|---|---|
| `uid` | string | yes | Component UID inside a scroll pane |
| `direction` | string | no | `UP`, `DOWN` (default), `LEFT`, `RIGHT` |
| `amount` | number | no | Scroll units (default 3) |
