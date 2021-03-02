async function addIncomeHandler(event) {
    const name = document.querySelector('#incomeName').value;
    const amount = document.querySelector('#incomeAmount').value;
    let month_year_income = document.querySelector('.month-and-year').innerHTML.split(' ');
    const month = month_year_income[0];
    const year = month_year_income[1];

    const response = await fetch('/api/income', {
        method: 'POST',
        body: JSON.stringify({
            name,
            amount,
            month,
            year
        }),
        headers: {
            'Content-Type': 'application/json'
        }
    });

    if (response.ok) {
        document.location.reload();
    } else {
        alert(response.statusText)
    }
};

async function getIncomeHandler(event) {

    let month_year_income = document.querySelector('.month-and-year').innerHTML.split(' ');
    const month = month_year_income[0];
    const year = month_year_income[1];

    const response = await fetch(`/api/users/income/${month}/${year}`, {
        method: 'GET',
        headers: {
            'Content-Type': 'application/json'
        }
    });

    if (response.ok) {
        response.json().then(text => {
            const incomeDiv = document.querySelector('.income-output-wrapper');
            const totalIncomeDiv = document.querySelector('.total-income');

            totalIncomeDiv.innerHTML = "";

            const incomeArr = text.incomes;
            let totalIncome = 0;

            incomeArr.forEach(income => {

                let incomeItemsDiv = document.createElement('div');
                let incomeName = document.createElement('p');
                let incomeAmount = document.createElement('p');
                let incomeDelete = document.createElement('button');

                incomeName.innerHTML = income.name;
                incomeAmount.innerHTML = income.amount;
                incomeDelete.innerHTML = 'Delete'

                incomeDelete.classList.add('delete-income');
                incomeItemsDiv.classList.add('income-items');

                incomeDelete.setAttribute('id', `${income.id}`)

                incomeItemsDiv.append(incomeName);
                incomeItemsDiv.append(incomeAmount);
                incomeItemsDiv.append(incomeDelete)
                incomeDiv.append(incomeItemsDiv);
                totalIncome = totalIncome + parseInt(income.amount);
            })


            totalIncomeDiv.innerHTML = `Total Income: $${totalIncome}`;

            async function deleteIncome(event) {

                const id = event.target.getAttribute('id');

                const response = await fetch(`/api/income/${id}`, {
                    method: 'DELETE',
                    headers: {
                        'Content-Type': 'application/json'
                    }
                })
                if (response.ok) {
                    document.location.reload();
                } else {
                    alert(response.statusText)
                }
            }

            document.querySelectorAll('.delete-income').forEach(btn => {
                btn.addEventListener('click', deleteIncome)
            });

        })
    } else {
        response.json().then(text => {
            const incomeDiv = document.querySelector('.income-output-wrapper');
            incomeDiv.innerHTML = ''
            incomeDiv.append(text.message)
        })
    }
};

getIncomeHandler();


document.querySelector('.add-income').addEventListener('click', addIncomeHandler);