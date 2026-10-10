Take-Home Quiz — Timeline / Gantt Scheduler
Overview
Build a horizontally scrollable timeline scheduler (think of the scheduling view in project-management tools). The timeline shows a set of task bars laid out against a time axis. Users can scroll through time, drag task bars around, resize them, and have them snap to a time grid.
Implement this using Jetpack Compose.
You may not use a third-party charting/Gantt library for the core view. The point is to see how you build custom, interactive UI in Compose from the ground up — working with layout, drawing, and gesture handling yourself. Standard Compose / AndroidX APIs are fine.

Time Expectation:
Please spend no more than 5 hours on this assignment. We encourage you to use AI tools throughout the process, as this reflects how we work at PicCollage.
If you don’t finish everything within the time limit, that’s completely fine—please list the remaining items, and we’ll discuss your approach and priorities during the review session.

The Task
A "task" has, at minimum: an id, a title, a start time, and a duration (or end time). Render tasks as horizontal bars positioned along a time axis. Multiple tasks are stacked in separate rows.
Required
Time axis header with readable tick labels (e.g. hours or days) that stays aligned with the content as the user scrolls horizontally.
Horizontal scrolling across the timeline, with the header and the content area scrolling in sync.
Task bars rendered in rows, correctly positioned and sized according to each task's start time and duration.
Drag to move a task bar along the time axis, updating its start time.
Resize a task bar by dragging its left/right edge, updating its start time or duration.
Snapping: when moving or resizing, task bars snap to a configurable time grid (e.g. every 15 minutes / every hour).
The following must be configurable (a parameter, state, or setter is fine — your choice):
time scale / unit of the axis
zoom level (how much horizontal space one time unit occupies)
snap interval
row height
A small amount of sample data so the screen is populated when we run it.
Free to explore
Anything not specified above is intentionally left open. Where the spec is silent — styling, colors, exact interaction feel, data model details, state management, architecture, how you structure the configurable parameters — use your own judgement. We're interested in the choices you make and why.
You are also welcome to add extra features beyond this list if you think they strengthen the submission. If you do, mention them in your writeup so we don't miss them.

Deliverables
A runnable Android project with the Compose implementation. A single screen that shows the scheduler is enough.
You can push it to github or provide us a link of the git bundle or zip file of the project folder, but please be sure to include the git history of your work.
A short README / writeup (see below).
Please keep the project self-contained and buildable with a standard, recent Android Studio / Gradle setup. Note any special steps needed to run it.

AI Usage Writeup (important)
Please use AI tools however you normally would. We are not trying to catch you using AI — we want to see how well you use it. In your writeup, please cover:
Which AI tools you used and how you broke the problem down and prompted them.
What the AI got wrong or produced poorly, how you noticed (e.g. profiling, testing a specific interaction, reading the generated code), and how you fixed it.
Your assessment of the final solution: what you think is good, what is a compromise, and what you would improve with more time.
Where the AI struggled most in this task (e.g. gesture handling, layout, scroll sync, performance) and why you think that is.
Where you overrode the AI or chose to write something yourself, and your reasoning.
Being able to produce working code is the baseline. What we really want to see is how you evaluate, critique, and steer what the AI gives you.
Please write directly in this document

Practicalities
Suggested time budget: a few focused hours. This is not meant to consume your whole weekend — if you run out of time, prioritize the required items and explain your trade-offs in the writeup.
If anything is ambiguous, make a reasonable assumption or reach out to us.
Good luck, and have fun with it.


