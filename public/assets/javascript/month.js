function load() {

    let month;
    let year;

    month = JSON.parse(localStorage.getItem("month"));
    year = JSON.parse(localStorage.getItem("year"));

    const dt = new Date();

    if (!month) {
        localStorage.setItem('month', JSON.stringify(dt.toLocaleDateString('en-us', { month: 'long' })));
        localStorage.setItem('counter', JSON.stringify(0))
    }
    if (!year) {
        localStorage.setItem('year', JSON.stringify(dt.getFullYear()))
    }


    document.querySelector('.next').addEventListener('click', () => {
        let counter = JSON.parse(localStorage.getItem('counter'))
        counter = counter + 1;
        dt.setMonth(new Date().getMonth() + counter)
        localStorage.clear();
        localStorage.setItem('counter', JSON.stringify(counter))
        localStorage.setItem('month', JSON.stringify(dt.toLocaleDateString('en-us', { month: 'long' })))
        localStorage.setItem('year', JSON.stringify(dt.getFullYear()))
        document.location.reload()
    })

    document.querySelector('.back').addEventListener('click', () => {
        let counter = JSON.parse(localStorage.getItem('counter'))
        counter = counter - 1;
        dt.setMonth(new Date().getMonth() + counter)
        localStorage.clear();
        localStorage.setItem('counter', JSON.stringify(counter))
        localStorage.setItem('month', JSON.stringify(dt.toLocaleDateString('en-us', { month: 'long' })))
        localStorage.setItem('year', JSON.stringify(dt.getFullYear()))
        document.location.reload()
    })

    document.querySelector('.month-and-year').innerHTML =
        `${month} ${year}`
}


load()