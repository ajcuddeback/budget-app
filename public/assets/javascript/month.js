let nav = 0;

function load() {
    const dt = new Date();

    if (nav !== 0) {
        dt.setMonth(new Date().getMonth() + nav)
    }

    const year = dt.getFullYear();

    document.querySelector('.month-and-year').innerHTML =
        `${dt.toLocaleDateString('en-us', { month: 'long' })} ${year}`
}

function initButtons() {
    document.querySelector('.back').addEventListener('click', () => {
        nav--;
        load();
    });

    document.querySelector('.next').addEventListener('click', () => {
        nav++;
        load();
    })
}

initButtons()
load()